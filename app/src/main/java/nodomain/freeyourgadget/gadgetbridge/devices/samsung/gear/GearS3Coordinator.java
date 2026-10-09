package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3HeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.GearS3DeviceSupport;

/** Milestone 1 coordinator for the Bluetooth Classic Gear S3 Frontier. */
public class GearS3Coordinator extends AbstractBLClassicDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        // SM-R760 Classic inquiry names have the form "Gear S3 (xxxx)".
        return Pattern.compile("Gear S3 \\(.*\\)");
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return GearS3DeviceSupport.class;
    }

    @Override
    @StringRes
    public int getDeviceNameResource() {
        return R.string.devicetype_samsung_gear_s3;
    }

    @Override
    public String getManufacturer() {
        return "Samsung";
    }

    @Override
    public int getBondingStyle() {
        // Milestone 1 adopts the existing BR/EDR bond. Do not send a dual-mode Gear through
        // CompanionDeviceManager's BLE-address association flow: its LE address differs from
        // the Classic address used by the SAP RFCOMM socket.
        return BONDING_STYLE_NONE;
    }

    @Override
    public boolean suggestUnbindBeforePair() {
        // The existing BR/EDR bond is required for the SAP RFCOMM connection.
        return false;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.WATCH;
    }

    @Override
    public boolean supportsMusicInfo(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsWeather(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsManualHeartRateMeasurement(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        // Host-triggered CommonSync is not implemented. Do not expose
        // a refresh action that cannot initiate a new watch sync while idle.
        return false;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(final GBDevice device) {
        return new int[]{R.xml.devicesettings_sync_calendar};
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        final Map<AbstractDao<?, ?>, Property> daos = new LinkedHashMap<>();
        daos.put(session.getGearS3ActivitySampleDao(), GearS3ActivitySampleDao.Properties.DeviceId);
        daos.put(session.getGearS3HeartRateSampleDao(), GearS3HeartRateSampleDao.Properties.DeviceId);
        return daos;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(@NonNull final GBDevice device,
                                                                       @NonNull final DaoSession session) {
        return new GearS3ActivitySampleProvider(device, session);
    }

    @Override
    public ActivityTrackProvider getActivityTrackProvider(@NonNull final GBDevice device,
                                                          @NonNull final Context context) {
        return new GearS3ExerciseTrackProvider();
    }
}
