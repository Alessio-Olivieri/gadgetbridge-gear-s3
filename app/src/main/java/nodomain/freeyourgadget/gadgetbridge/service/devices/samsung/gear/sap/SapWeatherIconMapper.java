package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

/**
 * Maps OpenWeatherMap condition codes to the normalized 0..23 Gear weather-icon
 * namespace and corresponding TWC representative codes.
 */
public final class SapWeatherIconMapper {
    private SapWeatherIconMapper() { }

    public static int toSamsungConvertedIcon(final int owmCode) {
        if (owmCode >= 200 && owmCode <= 232) return 8; // thunder
        if (owmCode >= 300 && owmCode <= 321) return 5; // shower
        if (owmCode >= 500 && owmCode <= 504) return 4; // rain
        if (owmCode == 511 || (owmCode >= 611 && owmCode <= 616)) return 15; // rain and snow
        if (owmCode >= 520 && owmCode <= 531) return 5; // shower
        if (owmCode >= 600 && owmCode <= 602) return 13; // snow
        if (owmCode >= 620 && owmCode <= 622) return 13; // snow shower
        if (owmCode >= 700 && owmCode <= 799 && owmCode != 781) return owmCode == 731 || owmCode == 751 || owmCode == 761 ? 22 : 3;
        if (owmCode == 781 || owmCode == 900 || owmCode == 901 || owmCode == 902 || owmCode == 962) return 23;
        if (owmCode == 800) return 0;
        if (owmCode == 801 || owmCode == 802) return 1;
        if (owmCode == 803 || owmCode == 804) return 2;
        if (owmCode == 903) return 18;
        if (owmCode == 904) return 17;
        if (owmCode == 905 || (owmCode >= 951 && owmCode <= 959)) return 19;
        if (owmCode == 906) return 15;
        return 1; // Gadgetbridge's existing generic fallback is partly-cloudy (02d).
    }

    /** A TWC provider-native representative for the selected Samsung icon. */
    public static int toTWCNativeIcon(final int samsungConvertedIcon) {
        switch (samsungConvertedIcon) {
            case 0: return 32;
            case 1: return 28;
            case 2: return 26;
            case 3: return 20;
            case 4: return 11;
            case 5: return 40;
            case 7: return 39;
            case 8: return 3;
            case 9: return 37;
            case 10: return 14;
            case 11: return 13;
            case 12: return 41;
            case 13: return 42;
            case 14: return 16;
            case 15: return 5;
            case 16: return 15;
            case 17: return 36;
            case 18: return 25;
            case 19: return 23;
            case 20: return 4;
            case 22: return 19;
            case 23: return 0;
            default: return 28;
        }
    }
}
