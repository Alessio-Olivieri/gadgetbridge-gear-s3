package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Active genuine Gear windows; a change request alone is not replacement range information. */
public final class SapCalendarRangeSet {
    private final List<SapCalendarCodec.RequestRange> ranges = new ArrayList<>();
    private final List<String> zones = new ArrayList<>();
    private boolean replaceOnNextRange;

    public List<SapCalendarCodec.RequestRange> getRanges() { return Collections.unmodifiableList(ranges); }
    public List<String> getZones() { return Collections.unmodifiableList(zones); }
    public boolean isReplacementPending() { return replaceOnNextRange; }
    public void requestReplacement() { replaceOnNextRange = true; }

    public void restore(final SapCalendarRangeStore.State state) {
        clear();
        ranges.addAll(state.ranges);
        zones.addAll(state.zones);
        replaceOnNextRange = state.replaceOnNextRange;
    }

    /** Called only after the request was successfully parsed and validated. */
    public void observe(final SapCalendarCodec.RequestRange range, final String zone) {
        if (range == null || zone == null) throw new IllegalArgumentException("Missing Gear range");
        if (replaceOnNextRange) clear();
        for (final SapCalendarCodec.RequestRange known : ranges) {
            if (known.start == range.start && known.end == range.end
                    && known.maxTextLength == range.maxTextLength && known.maxEvents == range.maxEvents
                    && known.maxAllDay == range.maxAllDay && known.reminders == range.reminders) return;
        }
        ranges.add(range);
        zones.add(zone);
    }

    public void clear() {
        ranges.clear();
        zones.clear();
        replaceOnNextRange = false;
    }
}
