const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { setGlobalOptions } = require('firebase-functions/v2');
const admin = require('firebase-admin');
const crypto = require('crypto');

admin.initializeApp();
setGlobalOptions({ maxInstances: 10, region: 'us-central1' });
const db = admin.firestore();
const FieldValue = admin.firestore.FieldValue;

function requireAuth(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Please sign in.');
  return request.auth.uid;
}

function cleanCode() {
  return crypto.randomBytes(5).toString('hex').toUpperCase();
}

function hashCode(code) {
  return crypto.createHash('sha256').update(code).digest('hex');
}

function tokenDocumentId(token) {
  return crypto.createHash('sha256').update(token).digest('hex').slice(0, 24);
}

exports.createGroup = onCall(async (request) => {
  const uid = requireAuth(request);
  const { name, description = '', latitude, longitude, radiusMeters } = request.data || {};
  if (
    typeof name !== 'string' || name.trim().length < 2 || name.trim().length > 60 ||
    typeof latitude !== 'number' || latitude < -90 || latitude > 90 ||
    typeof longitude !== 'number' || longitude < -180 || longitude > 180 ||
    !Number.isInteger(radiusMeters) || radiusMeters < 100 || radiusMeters > 5000
  ) {
    throw new HttpsError('invalid-argument', 'Invalid group name, coordinates, or radius (100–5000 m).');
  }

  let code;
  let groupRef;
  let created = false;

  for (let i = 0; i < 5 && !created; i++) {
    code = cleanCode();
    groupRef = db.collection('groups').doc();
    try {
      await db.runTransaction(async tx => {
        const codeRef = db.collection('invitationCodes').doc(hashCode(code));
        const existing = await tx.get(codeRef);
        if (existing.exists) throw new HttpsError('already-exists', 'Please retry.');

        tx.set(groupRef, {
          name: name.trim(),
          description: String(description).slice(0, 300),
          ownerUid: uid,
          invitationCode: code,
          invitationCodeHash: hashCode(code),
          latitude,
          longitude,
          radiusMeters,
          monitoringEnabled: true,
          configurationVersion: 1,
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp()
        });

        tx.set(groupRef.collection('members').doc(uid), {
          uid,
          role: 'owner',
          joinedAt: FieldValue.serverTimestamp(),
          monitoringEnabled: true,
          notificationPreferences: { enter: true, exit: true }
        });

        tx.set(db.collection('users').doc(uid).collection('memberships').doc(groupRef.id), {
          groupId: groupRef.id,
          joinedAt: FieldValue.serverTimestamp()
        });

        tx.set(codeRef, { groupId: groupRef.id, createdAt: FieldValue.serverTimestamp() });
      });
      created = true;
    } catch (e) {
      if (e.code === 'already-exists' && i < 4) {
        continue;
      }
      throw e;
    }
  }

  if (!created) {
    throw new HttpsError('aborted', 'Could not generate a unique invitation code. Please retry.');
  }

  return { groupId: groupRef.id, invitationCode: code };
});

exports.joinGroup = onCall(async (request) => {
  const uid = requireAuth(request);
  const code = String((request.data || {}).invitationCode || '').trim().toUpperCase();
  if (!/^[A-F0-9]{10}$/.test(code)) throw new HttpsError('invalid-argument', 'Invitation code is invalid.');

  const codeHash = hashCode(code);
  const codeSnap = await db.collection('invitationCodes').doc(codeHash).get();
  if (!codeSnap.exists) throw new HttpsError('not-found', 'Invitation code is invalid.');

  const groupId = codeSnap.get('groupId');
  const groupRef = db.collection('groups').doc(groupId);

  await db.runTransaction(async tx => {
    const group = await tx.get(groupRef);
    if (!group.exists || !group.get('monitoringEnabled')) throw new HttpsError('failed-precondition', 'Group is unavailable.');

    const memberRef = groupRef.collection('members').doc(uid);
    const member = await tx.get(memberRef);
    if (member.exists) throw new HttpsError('already-exists', 'You already belong to this group.');

    tx.set(memberRef, {
      uid,
      role: 'member',
      joinedAt: FieldValue.serverTimestamp(),
      monitoringEnabled: true,
      notificationPreferences: { enter: true, exit: true },
      invitationCodeHash: codeHash
    });

    tx.set(db.collection('users').doc(uid).collection('memberships').doc(groupId), {
      groupId,
      joinedAt: FieldValue.serverTimestamp(),
      invitationCodeHash: codeHash
    });
  });

  return { groupId };
});

exports.leaveGroup = onCall(async (request) => {
  const uid = requireAuth(request);
  const groupId = String((request.data || {}).groupId || '');
  if (!groupId) throw new HttpsError('invalid-argument', 'groupId is required.');

  const groupRef = db.collection('groups').doc(groupId);
  const memberRef = groupRef.collection('members').doc(uid);

  await db.runTransaction(async tx => {
    const group = await tx.get(groupRef);
    const member = await tx.get(memberRef);
    if (!group.exists || !member.exists) throw new HttpsError('not-found', 'Membership not found.');

    if (member.get('role') === 'owner') {
      const allMembersSnap = await groupRef.collection('members').get();
      const otherMembers = allMembersSnap.docs.filter(d => d.id !== uid);
      if (otherMembers.length === 0) {
        // Owner is the only member: delete entire group
        const codeHash = group.get('invitationCodeHash');
        if (codeHash) tx.delete(db.collection('invitationCodes').doc(codeHash));
        tx.delete(memberRef);
        tx.delete(db.collection('users').doc(uid).collection('memberships').doc(groupId));
        tx.delete(groupRef);
        return;
      } else {
        // Transfer ownership to the next existing member
        const nextOwnerUid = otherMembers[0].id;
        tx.update(groupRef, { ownerUid: nextOwnerUid, updatedAt: FieldValue.serverTimestamp() });
        tx.update(groupRef.collection('members').doc(nextOwnerUid), { role: 'owner' });
      }
    }

    tx.delete(memberRef);
    tx.delete(db.collection('users').doc(uid).collection('memberships').doc(groupId));
  });

  return { ok: true };
});

exports.deleteGroup = onCall(async (request) => {
  const uid = requireAuth(request);
  const groupId = String((request.data || {}).groupId || '');
  if (!groupId) throw new HttpsError('invalid-argument', 'groupId is required.');

  const groupRef = db.collection('groups').doc(groupId);
  const group = await groupRef.get();
  if (!group.exists) throw new HttpsError('not-found', 'Group not found.');
  if (group.get('ownerUid') !== uid) throw new HttpsError('permission-denied', 'Only the group admin can delete this group.');

  const codeHash = group.get('invitationCodeHash');
  const members = await groupRef.collection('members').get();

  const batch = db.batch();
  for (const m of members.docs) {
    batch.delete(m.ref);
    batch.delete(db.collection('users').doc(m.id).collection('memberships').doc(groupId));
  }
  if (codeHash) {
    batch.delete(db.collection('invitationCodes').doc(codeHash));
  }
  batch.delete(groupRef);
  await batch.commit();

  return { ok: true };
});

exports.processGeofenceEvent = onCall(async (request) => {
  const uid = requireAuth(request);
  const d = request.data || {};
  const groupId = String(d.groupId || '');
  const eventId = String(d.eventId || '');
  const type = d.eventType;
  const version = Number(d.configurationVersion);
  const clientTime = Number(d.clientEventTime);

  if (
    !groupId ||
    !/^[A-Fa-f0-9-]{20,50}$/.test(eventId) ||
    !['ENTER', 'EXIT'].includes(type) ||
    !Number.isInteger(version) ||
    !Number.isFinite(clientTime) ||
    Math.abs(Date.now() - clientTime) > 10 * 60 * 1000
  ) {
    throw new HttpsError('invalid-argument', 'Malformed or stale event.');
  }

  const groupRef = db.collection('groups').doc(groupId);
  const memberRef = groupRef.collection('members').doc(uid);
  const eventRef = groupRef.collection('events').doc(eventId);

  const result = await db.runTransaction(async tx => {
    const [g, m, old] = await Promise.all([tx.get(groupRef), tx.get(memberRef), tx.get(eventRef)]);

    if (old.exists) {
      const status = old.get('processingStatus');
      if (status === 'processed') {
        return { duplicate: true };
      }
      if (status === 'processing') {
        const receivedAt = old.get('serverReceivedAt');
        const age = receivedAt && receivedAt.toMillis ? (Date.now() - receivedAt.toMillis()) : 0;
        if (age < 30000) {
          return { duplicate: true };
        }
      }
    }

    if (!g.exists) {
      throw new HttpsError('not-found', 'Group does not exist.');
    }

    if (!m.exists) {
      throw new HttpsError('permission-denied', 'You are not a member of this group.');
    }

    if (g.get('monitoringEnabled') === false) {
      throw new HttpsError('failed-precondition', 'Group monitoring is disabled.');
    }

    if (g.get('configurationVersion') !== version) {
      throw new HttpsError('failed-precondition', 'Stale geofence configuration.');
    }

    tx.set(eventRef, {
      eventId,
      memberUid: uid,
      eventType: type,
      clientEventTime: new Date(clientTime),
      serverReceivedAt: FieldValue.serverTimestamp(),
      configurationVersion: version,
      processingStatus: 'processing'
    }, { merge: true });

    return { duplicate: false, groupName: g.get('name') || 'Group', memberUid: uid };
  });

  if (result.duplicate) {
    console.log(`Duplicate or in-flight event ignored: ${eventId} for group ${groupId}`);
    return { accepted: true, duplicate: true };
  }

  try {
    // 1. Identify all current members of the group without filtering by monitoringEnabled
    const membersSnap = await groupRef.collection('members').get();
    const sender = await db.collection('users').doc(uid).get();
    const displayName = sender.get('displayName') || sender.get('email') || 'A member';
    const body = type === 'ENTER'
      ? `${displayName} has entered the group's designated area.`
      : `${displayName} has left the group's designated area.`;
    const title = `${result.groupName} — Location Alert`;

    // 2. Exclude sender from remote push and respect each recipient's notification preferences
    const recipientDocs = membersSnap.docs.filter(doc => {
      if (doc.id === uid) return false;
      const prefs = doc.get('notificationPreferences') || {};
      const prefKey = type.toLowerCase();
      if (prefs[prefKey] === false) return false;
      return true;
    });

    // 3. Retrieve registered FCM device tokens and fan-out notification docs to recipients
    const tokens = [];
    await Promise.all(recipientDocs.map(async (memberDoc) => {
      const memberUid = memberDoc.id;
      try {
        const [deviceSnap] = await Promise.all([
          db.collection('users').doc(memberUid).collection('devices').get(),
          db.collection('users').doc(memberUid).collection('notifications').doc(eventId).set({
            groupId,
            groupName: result.groupName,
            eventId,
            eventType: type,
            memberUid: uid,
            memberName: displayName,
            title,
            body,
            createdAt: FieldValue.serverTimestamp(),
            read: false,
            processingStatus: 'queued'
          }, { merge: true })
        ]);

        for (const device of deviceSnap.docs) {
          const token = device.get('token');
          if (token && typeof token === 'string' && token.trim().length > 0) {
            tokens.push({ token: token.trim(), uid: memberUid, deviceId: device.id });
          }
        }
      } catch (err) {
        console.error(`Failed preparing notification for recipient ${memberUid}:`, err.message);
      }
    }));

    // Record notification for the triggering member so their local history has it
    const myBody = type === 'ENTER'
      ? `${displayName} (You) has entered ${result.groupName}'s designated area.`
      : `${displayName} (You) has left ${result.groupName}'s designated area.`;
    await db.collection('users').doc(uid).collection('notifications').doc(eventId).set({
      groupId,
      groupName: result.groupName,
      eventId,
      eventType: type,
      memberUid: uid,
      memberName: displayName,
      title,
      body: myBody,
      createdAt: FieldValue.serverTimestamp(),
      read: false,
      processingStatus: 'processed'
    }, { merge: true });

    // 4. Deduplicate tokens across devices
    const seenTokens = new Set();
    const uniqueTokens = [];
    for (const t of tokens) {
      if (!seenTokens.has(t.token)) {
        seenTokens.add(t.token);
        uniqueTokens.push(t);
      }
    }

    console.log(`Geofence event ${eventId} (${type}): Eligible recipients: ${recipientDocs.length}, Unique device tokens: ${uniqueTokens.length}`);

    let totalSuccessCount = 0;
    let totalFailureCount = 0;
    const invalidTokens = [];

    // 5. Send FCM push notifications in batches of up to 500
    if (uniqueTokens.length > 0) {
      for (let i = 0; i < uniqueTokens.length; i += 500) {
        const chunk = uniqueTokens.slice(i, i + 500);
        try {
          const response = await admin.messaging().sendEachForMulticast({
            tokens: chunk.map(x => x.token),
            notification: { title, body },
            data: {
              title: String(title),
              body: String(body),
              groupId: String(groupId),
              groupName: String(result.groupName),
              memberName: String(displayName),
              memberUid: String(uid),
              eventId: String(eventId),
              eventType: String(type)
            },
            android: {
              priority: 'high',
              notification: {
                channelId: 'groupfence_alerts',
                sound: 'default'
              }
            }
          });

          totalSuccessCount += response.successCount;
          totalFailureCount += response.failureCount;

          console.log(`Multicast batch [${i}..${i + chunk.length - 1}]: ${response.successCount} succeeded, ${response.failureCount} failed.`);

          response.responses.forEach((r, idx) => {
            if (!r.success) {
              const errCode = r.error ? r.error.code : 'unknown';
              const errMsg = r.error ? r.error.message : '';
              const recipient = chunk[idx];
              console.warn(`FCM delivery failure for member ${recipient.uid} (token prefix: ${recipient.token.slice(0, 6)}...): [${errCode}] ${errMsg}`);
              if (
                [
                  'messaging/registration-token-not-registered',
                  'messaging/invalid-registration-token',
                  'messaging/invalid-argument'
                ].includes(errCode)
              ) {
                invalidTokens.push(recipient);
              }
            }
          });
        } catch (chunkErr) {
          console.error(`FCM multicast dispatch failed for batch [${i}..${i + chunk.length - 1}]:`, chunkErr);
        }
      }
    }

    // 6. Safely remove invalid or expired tokens without interrupting delivery
    if (invalidTokens.length > 0) {
      console.log(`Cleaning up ${invalidTokens.length} stale/invalid device tokens.`);
      const cleanupBatch = db.batch();
      for (const item of invalidTokens) {
        const docId = item.deviceId || tokenDocumentId(item.token);
        cleanupBatch.delete(db.collection('users').doc(item.uid).collection('devices').doc(docId));
      }
      try {
        await cleanupBatch.commit();
      } catch (cleanupErr) {
        console.warn('Error during invalid tokens cleanup:', cleanupErr.message);
      }
    }

    // 7. Mark event as successfully processed in Firestore
    await eventRef.set({
      processingStatus: 'processed',
      processedAt: FieldValue.serverTimestamp(),
      groupName: result.groupName,
      memberName: displayName,
      title,
      recipientCount: recipientDocs.length,
      deliveredTokens: totalSuccessCount
    }, { merge: true });

    console.log(`Event ${eventId} processed. Recipients: ${recipientDocs.length}, Tokens delivered: ${totalSuccessCount}, failed: ${totalFailureCount}`);

    return { accepted: true, recipients: recipientDocs.length, deliveredTokens: totalSuccessCount };
  } catch (error) {
    console.error(`Error processing geofence event ${eventId}:`, error);
    try {
      await eventRef.set({
        processingStatus: 'failed',
        lastError: error.message || String(error),
        failedAt: FieldValue.serverTimestamp()
      }, { merge: true });
    } catch (e) {
      console.error(`Failed to update failure status for event ${eventId}:`, e.message);
    }
    throw error;
  }
});
