package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Raw SAP agent metadata as exchanged by CapEx. */
public final class SapAgentDescriptor {
    private final int uuid;
    private final String friendlyName;
    private final List<SapServiceDescriptor> services;

    public SapAgentDescriptor(final int uuid, final String friendlyName, final List<SapServiceDescriptor> services) {
        if (uuid < 0 || uuid > 0xffff || friendlyName == null || services == null) throw new IllegalArgumentException("Invalid SAP agent descriptor");
        this.uuid = uuid;
        this.friendlyName = friendlyName;
        this.services = Collections.unmodifiableList(new ArrayList<>(services));
    }
    public int getUuid() { return uuid; }
    public String getFriendlyName() { return friendlyName; }
    public List<SapServiceDescriptor> getServices() { return services; }
}
