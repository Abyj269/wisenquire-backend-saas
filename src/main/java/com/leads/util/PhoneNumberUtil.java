package com.leads.util;

/** Preserves country codes; never merge customers by subscriber-number suffix. */
public final class PhoneNumberUtil {


    private PhoneNumberUtil() {
    }

    /** Compatibility method name; returns all digits, normalizing the international 00 prefix. */
    public static String lastSignificantDigits(String rawPhone) {
        if (rawPhone == null) return "";
        String digits=rawPhone.replaceAll("\\D", "");
        return digits.startsWith("00") ? digits.substring(2) : digits;
    }
    public static String canonical(String rawPhone) {
        if (rawPhone == null || rawPhone.length() > 50 || !rawPhone.matches("[+0-9() .-]+")) throw new IllegalArgumentException("Use an international phone number without extensions or letters.");
        String digits=lastSignificantDigits(rawPhone);
        if(!digits.matches("[1-9][0-9]{7,14}")) throw new IllegalArgumentException("Enter the complete international phone number including country code.");
        return digits;
    }
}
