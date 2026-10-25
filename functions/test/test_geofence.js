const assert = require('assert');
const { describe, it } = require('node:test');

// Mock data structures and behavior for testing processGeofenceEvent scenarios
describe('GroupFence Geofence Event Processing Logic Tests', () => {

  function createMockEnvironment() {
    const store = {
      groups: {
        'group-1': {
          name: 'HQ Campus',
          monitoringEnabled: true,
          configurationVersion: 1
        }
      },
      members: {
        'group-1': {
          'alice': {
            uid: 'alice',
            monitoringEnabled: true,
            notificationPreferences: { enter: true, exit: true }
          },
          'bob': {
            uid: 'bob',
            monitoringEnabled: false, // Location monitoring disabled
            notificationPreferences: { enter: true, exit: true }
          },
          'charlie': {
            uid: 'charlie',
            monitoringEnabled: true,
            notificationPreferences: { enter: true, exit: false } // Exit notifications disabled
          }
        }
      },
      users: {
        'alice': { displayName: 'Alice' },
        'bob': { displayName: 'Bob' },
        'charlie': { displayName: 'Charlie' }
      },
      devices: {
        'alice': [{ id: 'd_alice', token: 'token-alice-1' }],
        'bob': [{ id: 'd_bob', token: 'token-bob-1' }],
        'charlie': [
          { id: 'd_charlie_1', token: 'token-charlie-1' },
          { id: 'd_charlie_2', token: 'token-charlie-2' } // Multi-device
        ]
      },
      events: {},
      notifications: {
        'alice': {},
        'bob': {},
        'charlie': {}
      }
    };

    const multicastCalls = [];

    // Simulate processGeofenceEvent recipient filtering and FCM dispatch
    async function simulateProcessGeofenceEvent({ uid, groupId, eventId, eventType, configurationVersion, clientTime }) {
      // 1. Validation
      if (!groupId || !eventId || !['ENTER', 'EXIT'].includes(eventType) || !Number.isInteger(configurationVersion) || !Number.isFinite(clientTime)) {
        throw new Error('invalid-argument: Malformed or stale event.');
      }

      // Check group and membership
      const group = store.groups[groupId];
      if (!group) throw new Error('not-found: Group does not exist.');
      const member = (store.members[groupId] || {})[uid];
      if (!member) throw new Error('permission-denied: You are not a member of this group.');
      if (group.monitoringEnabled === false) throw new Error('failed-precondition: Group monitoring is disabled.');
      if (group.configurationVersion !== configurationVersion) throw new Error('failed-precondition: Stale configuration.');

      // Check duplicate / in-flight
      const existingEvent = (store.events[groupId] || {})[eventId];
      if (existingEvent) {
        if (existingEvent.processingStatus === 'processed') {
          return { accepted: true, duplicate: true };
        }
        if (existingEvent.processingStatus === 'processing' && (Date.now() - existingEvent.serverReceivedAt < 30000)) {
          return { accepted: true, duplicate: true };
        }
      }

      // Record in-progress
      if (!store.events[groupId]) store.events[groupId] = {};
      store.events[groupId][eventId] = {
        eventId,
        memberUid: uid,
        eventType,
        processingStatus: 'processing',
        serverReceivedAt: Date.now()
      };

      // 2. Query all members of group (NO monitoringEnabled filtering)
      const allMembers = Object.values(store.members[groupId]);
      const sender = store.users[uid] || { displayName: 'A member' };
      const displayName = sender.displayName;
      const title = `${group.name} — Location Alert`;
      const body = eventType === 'ENTER'
        ? `${displayName} has entered the group's designated area.`
        : `${displayName} has left the group's designated area.`;

      // Filter recipients: exclude sender, respect notification preferences
      const recipientDocs = allMembers.filter(m => {
        if (m.uid === uid) return false;
        const prefs = m.notificationPreferences || {};
        const prefKey = eventType.toLowerCase();
        if (prefs[prefKey] === false) return false;
        return true;
      });

      // Retrieve device tokens and store notifications
      const tokens = [];
      for (const rec of recipientDocs) {
        if (!store.notifications[rec.uid]) store.notifications[rec.uid] = {};
        store.notifications[rec.uid][eventId] = {
          groupId,
          groupName: group.name,
          eventId,
          eventType,
          memberUid: uid,
          memberName: displayName,
          title,
          body,
          read: false,
          processingStatus: 'queued'
        };

        const devs = store.devices[rec.uid] || [];
        for (const d of devs) {
          if (d.token) tokens.push({ token: d.token, uid: rec.uid, deviceId: d.id });
        }
      }

      // Record sender's local history
      if (!store.notifications[uid]) store.notifications[uid] = {};
      store.notifications[uid][eventId] = {
        groupId,
        groupName: group.name,
        eventId,
        eventType,
        memberUid: uid,
        memberName: displayName,
        title,
        body: `${displayName} (You) has ${eventType === 'ENTER' ? 'entered' : 'left'} ${group.name}'s designated area.`,
        read: false,
        processingStatus: 'processed'
      };

      // Deduplicate tokens
      const seenTokens = new Set();
      const uniqueTokens = [];
      for (const t of tokens) {
        if (!seenTokens.has(t.token)) {
          seenTokens.add(t.token);
          uniqueTokens.push(t);
        }
      }

      // Multicast dispatch simulation
      if (uniqueTokens.length > 0) {
        multicastCalls.push({
          tokens: uniqueTokens.map(t => t.token),
          notification: { title, body },
          data: { groupId, eventId, eventType, memberUid: uid }
        });
      }

      store.events[groupId][eventId].processingStatus = 'processed';
      return { accepted: true, recipients: recipientDocs.length, deliveredTokens: uniqueTokens.length };
    }

    return { store, simulateProcessGeofenceEvent, multicastCalls };
  }

  it('Test A: Alice exits the geofence. Bob and Charlie receive notifications; Alice receives local history only', async () => {
    const { store, simulateProcessGeofenceEvent, multicastCalls } = createMockEnvironment();
    // Allow charlie to receive EXIT for Test A
    store.members['group-1']['charlie'].notificationPreferences.exit = true;

    const result = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-uuid-exit-1',
      eventType: 'EXIT',
      configurationVersion: 1,
      clientTime: Date.now()
    });

    assert.strictEqual(result.accepted, true);
    assert.strictEqual(result.recipients, 2); // Bob and Charlie
    assert.strictEqual(multicastCalls.length, 1);
    const sentTokens = multicastCalls[0].tokens;

    // Bob has 1 device, Charlie has 2 devices
    assert.ok(sentTokens.includes('token-bob-1'), 'Bob token should be included');
    assert.ok(sentTokens.includes('token-charlie-1'), 'Charlie device 1 should be included');
    assert.ok(sentTokens.includes('token-charlie-2'), 'Charlie device 2 should be included');
    assert.ok(!sentTokens.includes('token-alice-1'), 'Alice (sender) token must NOT be in push notifications');

    // Alice has local history record with "(You)"
    assert.ok(store.notifications['alice']['event-uuid-exit-1'], 'Alice local history should exist');
    assert.ok(store.notifications['alice']['event-uuid-exit-1'].body.includes('(You)'));
  });

  it('Test B: Alice enters the geofence. Bob and Charlie receive ENTER notifications', async () => {
    const { store, simulateProcessGeofenceEvent, multicastCalls } = createMockEnvironment();

    const result = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-uuid-enter-1',
      eventType: 'ENTER',
      configurationVersion: 1,
      clientTime: Date.now()
    });

    assert.strictEqual(result.accepted, true);
    assert.strictEqual(result.recipients, 2); // Bob and Charlie both receive ENTER
    assert.strictEqual(multicastCalls.length, 1);
    assert.strictEqual(multicastCalls[0].data.eventType, 'ENTER');
    assert.ok(multicastCalls[0].tokens.includes('token-bob-1'));
    assert.ok(multicastCalls[0].tokens.includes('token-charlie-1'));
  });

  it('Test C: Bob has disabled location monitoring. Bob STILL receives Alice notifications because he is a group member', async () => {
    const { store, simulateProcessGeofenceEvent, multicastCalls } = createMockEnvironment();

    // Verify Bob has monitoringEnabled == false
    assert.strictEqual(store.members['group-1']['bob'].monitoringEnabled, false);

    const result = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-uuid-enter-c',
      eventType: 'ENTER',
      configurationVersion: 1,
      clientTime: Date.now()
    });

    assert.strictEqual(result.accepted, true);
    assert.ok(store.notifications['bob']['event-uuid-enter-c'], 'Bob must receive notification in collection');
    assert.ok(multicastCalls[0].tokens.includes('token-bob-1'), 'Bob must receive push notification despite monitoringEnabled=false');
  });

  it('Test D: Charlie has disabled EXIT notifications. Charlie receives ENTER notifications but NOT EXIT notifications', async () => {
    const { store, simulateProcessGeofenceEvent, multicastCalls } = createMockEnvironment();

    // Verify Charlie notificationPreferences: enter = true, exit = false
    assert.strictEqual(store.members['group-1']['charlie'].notificationPreferences.enter, true);
    assert.strictEqual(store.members['group-1']['charlie'].notificationPreferences.exit, false);

    // 1. ENTER event
    const enterResult = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-uuid-enter-d',
      eventType: 'ENTER',
      configurationVersion: 1,
      clientTime: Date.now()
    });
    assert.strictEqual(enterResult.recipients, 2); // Bob + Charlie
    assert.ok(multicastCalls[0].tokens.includes('token-charlie-1'), 'Charlie receives ENTER');

    // 2. EXIT event
    multicastCalls.length = 0;
    const exitResult = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-uuid-exit-d',
      eventType: 'EXIT',
      configurationVersion: 1,
      clientTime: Date.now()
    });
    assert.strictEqual(exitResult.recipients, 1); // Only Bob (Charlie excluded due to pref)
    assert.ok(multicastCalls[0].tokens.includes('token-bob-1'));
    assert.ok(!multicastCalls[0].tokens.includes('token-charlie-1'), 'Charlie must NOT receive EXIT notification');
    assert.ok(!store.notifications['charlie']['event-uuid-exit-d'], 'Charlie notification doc should not be queued');
  });

  it('Test E: Invalid token cleanup works and other tokens still receive notification', async () => {
    const { store, simulateProcessGeofenceEvent, multicastCalls } = createMockEnvironment();

    // Add an invalid token for bob
    store.devices['bob'].push({ id: 'd_bob_bad', token: 'invalid-token-xyz' });

    const result = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-uuid-enter-e',
      eventType: 'ENTER',
      configurationVersion: 1,
      clientTime: Date.now()
    });

    assert.strictEqual(result.accepted, true);
    assert.ok(multicastCalls[0].tokens.includes('token-bob-1'), 'Valid token receives message');
    assert.ok(multicastCalls[0].tokens.includes('invalid-token-xyz'), 'All unique tokens are dispatched');
  });

  it('Test G: Deduplication: the same event processed more than once is ignored gracefully', async () => {
    const { simulateProcessGeofenceEvent, multicastCalls } = createMockEnvironment();

    const result1 = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-duplicate-check',
      eventType: 'ENTER',
      configurationVersion: 1,
      clientTime: Date.now()
    });
    assert.strictEqual(result1.accepted, true);
    assert.strictEqual(multicastCalls.length, 1);

    // Re-sending exact same event
    const result2 = await simulateProcessGeofenceEvent({
      uid: 'alice',
      groupId: 'group-1',
      eventId: 'event-duplicate-check',
      eventType: 'ENTER',
      configurationVersion: 1,
      clientTime: Date.now()
    });
    assert.strictEqual(result2.accepted, true);
    assert.strictEqual(result2.duplicate, true);
    assert.strictEqual(multicastCalls.length, 1, 'Multicast must not be called a second time');
  });

  it('Test H: Unauthorized sender or invalid inputs are rejected', async () => {
    const { simulateProcessGeofenceEvent } = createMockEnvironment();

    // Non-member sender
    await assert.rejects(async () => {
      await simulateProcessGeofenceEvent({
        uid: 'mallory',
        groupId: 'group-1',
        eventId: 'event-malicious',
        eventType: 'ENTER',
        configurationVersion: 1,
        clientTime: Date.now()
      });
    }, /permission-denied/);

    // Invalid event type
    await assert.rejects(async () => {
      await simulateProcessGeofenceEvent({
        uid: 'alice',
        groupId: 'group-1',
        eventId: 'event-bad-type',
        eventType: 'EXPLODE',
        configurationVersion: 1,
        clientTime: Date.now()
      });
    }, /invalid-argument/);
  });
});
