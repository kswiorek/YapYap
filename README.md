# YapYap

## The people's communicator

This is a work-in-progress project to develop a decentralized messaging app.
The design choices made here rely on the assumption that there are arount 20-40 people using the network.

### Premise

1. All peers run the same core backend package.
2. Peers use Tor and WebRTC to communicate.
3. Tor is used to allow unsolicited incoming traffic on all networks.
4. All messages are transmitted over Tor - texts and small files (< ~5 MB) by default.
5. WebRTC is used for fast messaging when the peer is online and sending files with no size limit.
6. To ensure messages can be delivered when a peer is offline, the messages are simultaneously sent to a number of other
   peers.
7. The selection of relay peers is done based on the uptime of the peers to ensure a given probability of success.
8. To ensure that the network can work even if all users are offline, a number of headless peers are run.
9. Relay messages are pruned after 2 days or they exceed a size limit set by the user.
10. The identity of the user is two-tiered: account and device, with devices belonging to accounts.
11. The identity of all devices and accounts is known to all other peers.
12. All accounts belong to a global room, where events about the network are broadcast.
13. All communications are end-to-end encrypted, including group chats and file transfers.
14. Sponsors onboard new users or devices by scanning a QR code with the keys of the newcomer.
15. The final goal is for the app to run on all platforms (desktop, android, iOS)

### Project structure

#### Main layers:

- `core` is the core backend package with `OrchestratorRuntime` exposing the API for the frontend.
- `Orchestrator` (layer) – the top layer of the `core` package, constructs all objects needed for other layers. If not a
  headless relay, starts `OrchestratorRuntime`.
    - `OrchestratorRuntime` exposes 4 services:
        - `AccountService` lists account, rooms, allows for room creation, etc. (TODO)
        - `MessagingService` handles all messaging-related operations, including file transfers.
        - `ConfigService` exposes the internal configuration of the network.
        - `OnboardingService` handles onboarding of new users and devices (sponsor side)
    - `DagEngine` handles the DAGs of each room, all messages go through the DAGs.
    - `InboundMessagePipeline` subscribes to the `incomingMessages` flow of the `Router`, appends them to the DAGs.
    - `OnboardingProvider` exposes the `OnboardingState`, receives the onboarding intro sent by the sonsor (newcomer
      side)
    - `RecoveryResponder` responds to recovery requests from a recovering device.
    - `SyncCoordinator` synchronizes the DAGs if orphaned messages are detected or after long periods of inactivity.
    - `GlobalEventProjector` manages the global room DAG, projects the events to the `accounts` and `devices` DB tables
    - `MaintenanceScheduler` - runs periodic maintenance tasks, like pruning stale DB rows.
- `Router` (layer) - ensures messages are delvered from an account level to account level.
    - `outbound` classes - send and protect their respective packet types
        - `OutboxProcessor` enqueues the packet outbox - periodically retries until a message is ACKed
        - `ProactiveSessionOpener` opens WebRTC sessions for peers with whom the user is typing and are online.
    - `InboundEnvelopeProcessor` processes all incoming envelopes from two transports, applies effects of the envelopes
        - `InboundEnvelopeHandler` interface for the handlers of all packet types.
    - `PingProvider` sends periodic pings to all known peers ensuring traffic, provides self-reported reliability score
      and list of chainable UUIDs of common DAGs
    - `PeerAvailabilityRegistry` tracks the availability of peers and their uptime scores.
    - `sync` handles sending and receiving sync requests.
        - `SyncHandler` replies to sync requests by sending the requested messages.
        - `SyncRetryProcessor` reads the pending sync requests from the DB and retries them periodically trying
          different peers.
        - `SyncPayloadProvider` gathers the messages needed for a sync request.
    - `transport` (layer)
        - TODO 