package com.rbdip.bookstore.support;

import java.util.Random;

public final class ContactTestData {

    private ContactTestData() {
    }

    public record Contacts(String fullName, String address, String phone) {
    }

    public static Contacts maximumLengthUnicodeContacts() {
        Random random = new Random(2026);
        return new Contacts(text(random, 255), text(random, 500), text(random, 50));
    }

    private static String text(Random random, int length) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++) {
            result.appendCodePoint(0x10000 + random.nextInt(0x100000));
        }
        return result.toString();
    }
}
