package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/** One SAP service-connection QoS triple. */
public final class SapQosDescriptor {
    private final int type;
    private final int dataRate;
    private final int classType;

    public SapQosDescriptor(final int type, final int dataRate, final int classType) {
        if (type < 0 || type > 0xff || dataRate < 0 || dataRate > 0xff || classType < 0 || classType > 0xff) {
            throw new IllegalArgumentException("Invalid SAP QoS descriptor");
        }
        this.type = type;
        this.dataRate = dataRate;
        this.classType = classType;
    }

    public int getType() { return type; }
    public int getDataRate() { return dataRate; }
    public int getClassType() { return classType; }
}
