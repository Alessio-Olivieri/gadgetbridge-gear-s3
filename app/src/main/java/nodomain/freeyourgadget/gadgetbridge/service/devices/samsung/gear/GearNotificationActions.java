package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;

/** Gear-visible identities for one active window; executors always come from its latest update. */
final class GearNotificationActions {
    private static final int MAX_COMPOSER_SEQUENCES = 64;
    private final String windowId;
    private final String packageName;
    private final Map<Integer, NotificationSpec.Action> actions;
    private final Map<Identity, Integer> reusableIds;
    // Connection/window-local provenance only; never retain previous Android executors.
    private final LinkedHashMap<Integer, Set<Integer>> composerSequences = new LinkedHashMap<>();

    private GearNotificationActions(final String windowId, final String packageName,
                                    final Map<Integer, NotificationSpec.Action> actions,
                                    final Map<Identity, Integer> reusableIds) {
        this.windowId = windowId;
        this.packageName = packageName;
        this.actions = Collections.unmodifiableMap(actions);
        this.reusableIds = reusableIds;
    }

    static GearNotificationActions update(final String windowId, final String packageName,
                                          final List<NotificationSpec.Action> current,
                                          final GearNotificationActions previous,
                                          final IntSupplier allocateId) {
        final List<NotificationSpec.Action> wearable = new ArrayList<>();
        final Map<Identity, Integer> counts = new HashMap<>();
        if (current != null) {
            for (final NotificationSpec.Action action : current) {
                if (action == null || (action.getType() != NotificationSpec.Action.TYPE_WEARABLE_REPLY
                        && action.getType() != NotificationSpec.Action.TYPE_WEARABLE_SIMPLE)) continue;
                wearable.add(action);
                counts.merge(new Identity(action), 1, Integer::sum);
            }
        }
        final boolean sameWindow = previous != null && Objects.equals(windowId, previous.windowId)
                && Objects.equals(packageName, previous.packageName);
        final Map<Integer, NotificationSpec.Action> actions = new LinkedHashMap<>();
        final Map<Identity, Integer> reusableIds = new HashMap<>();
        for (final NotificationSpec.Action action : wearable) {
            final Identity identity = new Identity(action);
            // Duplicates cannot be safely matched by order. Removed/changed identities are
            // absent from the next snapshot and cannot later revive an old composer action.
            final boolean unique = counts.get(identity) == 1;
            final Integer previousId = sameWindow && unique ? previous.reusableIds.get(identity) : null;
            final int id = previousId == null ? allocateId.getAsInt() : previousId;
            actions.put(id, action); // Deliberately bind the newest Android handle, never the old executor.
            if (unique) reusableIds.put(identity, id);
        }
        final GearNotificationActions updated = new GearNotificationActions(windowId, packageName, actions, reusableIds);
        if (sameWindow) {
            for (final Map.Entry<Integer, Set<Integer>> entry : previous.composerSequences.entrySet()) {
                final Set<Integer> compatible = new java.util.HashSet<>(entry.getValue());
                compatible.retainAll(reusableIds.values());
                updated.composerSequences.put(entry.getKey(), compatible);
            }
        }
        return updated;
    }

    void recordSequence(final int sequence) {
        composerSequences.remove(sequence);
        composerSequences.put(sequence, new java.util.HashSet<>(reusableIds.values()));
        while (composerSequences.size() > MAX_COMPOSER_SEQUENCES)
            composerSequences.remove(composerSequences.keySet().iterator().next());
    }

    boolean hasSequence(final int sequence) {
        return composerSequences.containsKey(sequence);
    }

    NotificationSpec.Action resolveStale(final int sequence, final int actionId, final String requestedWindowId) {
        final NotificationSpec.Action action = resolve(actionId, requestedWindowId);
        final Set<Integer> historical = composerSequences.get(sequence);
        // Only unambiguous continuous reply identities can recover a composer-held sequence.
        return action != null && action.isReply() && action.getRemoteInputKey() != null
                && !action.getRemoteInputKey().isEmpty() && reusableIds.containsValue(actionId)
                && historical != null && historical.contains(actionId) ? action : null;
    }

    NotificationSpec.Action get(final int actionId) {
        return actions.get(actionId);
    }

    NotificationSpec.Action resolve(final int actionId, final String requestedWindowId) {
        return Objects.equals(windowId, requestedWindowId) ? get(actionId) : null;
    }

    boolean containsKey(final int actionId) {
        return actions.containsKey(actionId);
    }

    Set<Integer> keySet() {
        return actions.keySet();
    }

    Set<Map.Entry<Integer, NotificationSpec.Action>> entrySet() {
        return actions.entrySet();
    }

    /** Conservative local identity, not an additional Samsung protocol field. */
    private static final class Identity {
        private final int type;
        private final String title;
        private final String resultKey;

        Identity(final NotificationSpec.Action action) {
            type = action.getType();
            title = action.getTitle() == null ? "" : action.getTitle();
            resultKey = action.getRemoteInputKey();
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Identity)) return false;
            final Identity identity = (Identity) other;
            return type == identity.type && Objects.equals(title, identity.title)
                    && Objects.equals(resultKey, identity.resultKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, title, resultKey);
        }
    }
}
