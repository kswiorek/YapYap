package org.yapyap.config

private val DEFAULTS = RuntimeConfig()

fun derive(user: UserPreferences, net: NetworkPolicy): RuntimeConfig =
    DEFAULTS.applyUser(user).applyNetwork(net)

// copies ONLY user-permitted fields
fun RuntimeConfig.applyUser(u: UserPreferences): RuntimeConfig = copy(
    router = router.copy(
        outboxMaxSizeBytes = u.router.outboxMaxSizeBytes ?: router.outboxMaxSizeBytes,
        ackLifetimeSeconds = u.router.ackLifetimeSeconds ?: router.ackLifetimeSeconds,
    ),
    sync = sync.copy(
        gracePeriodSeconds = u.sync.gracePeriodSeconds ?: sync.gracePeriodSeconds,
    ),
)

// copies ONLY network-controlled fields
fun RuntimeConfig.applyNetwork(n: NetworkPolicy): RuntimeConfig = copy(
    router = router.copy(
        messageLifetimeSeconds = n.router.messageLifetimeSeconds ?: router.messageLifetimeSeconds,
        maxMessageSizeBytes = n.router.maxMessageSizeBytes ?: router.maxMessageSizeBytes,
        dedupRetentionSeconds = n.router.dedupRetentionSeconds ?: router.dedupRetentionSeconds,
    ),
)

// patch merge for updateUser — null = unchanged
fun UserPreferences.mergePatch(patch: UserPreferences): UserPreferences = copy(
    router = router.mergePatch(patch.router),
    sync = sync.mergePatch(patch.sync),
    pushToken = patch.pushToken ?: pushToken,
)

private fun RouterUserPrefs.mergePatch(patch: RouterUserPrefs): RouterUserPrefs = copy(
    outboxMaxSizeBytes = patch.outboxMaxSizeBytes ?: outboxMaxSizeBytes,
    ackLifetimeSeconds = patch.ackLifetimeSeconds ?: ackLifetimeSeconds,
)

private fun SyncUserPrefs.mergePatch(patch: SyncUserPrefs): SyncUserPrefs = copy(
    gracePeriodSeconds = patch.gracePeriodSeconds ?: gracePeriodSeconds,
)

// inverse of applyNetwork — projects network-controlled fields back out of the
// persisted state.toml so the network policy can be cached across restarts.
fun fromRuntime(r: RuntimeConfig): NetworkPolicy = NetworkPolicy(
    router = RouterNetworkPolicy(
        messageLifetimeSeconds = r.router.messageLifetimeSeconds,
        maxMessageSizeBytes = r.router.maxMessageSizeBytes,
        dedupRetentionSeconds = r.router.dedupRetentionSeconds,
    ),
)
