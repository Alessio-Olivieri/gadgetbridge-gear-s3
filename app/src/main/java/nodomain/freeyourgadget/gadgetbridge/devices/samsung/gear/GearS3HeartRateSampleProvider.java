package nodomain.freeyourgadget.gadgetbridge.devices.samsung.gear;

import androidx.annotation.NonNull;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GearS3HeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/** Source-UUID keyed Gear S3 heart-rate history. */
public class GearS3HeartRateSampleProvider extends AbstractTimeSampleProvider<GearS3HeartRateSample> {
    public GearS3HeartRateSampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<GearS3HeartRateSample, ?> getSampleDao() {
        return getSession().getGearS3HeartRateSampleDao();
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return GearS3HeartRateSampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return GearS3HeartRateSampleDao.Properties.DeviceId;
    }

    @NonNull
    @Override
    public GearS3HeartRateSample createSample() {
        return new GearS3HeartRateSample();
    }
}
