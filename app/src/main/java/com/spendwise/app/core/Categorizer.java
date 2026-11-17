package com.spendwise.app.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Keyword-based first guess at a category. The user's own choices override this (see Repo.rememberMerchant). */
public final class Categorizer {
    private Categorizer() {}

    public static final String UNCATEGORISED = "Uncategorised";
    public static final String FOOD = "Food & Dining";

    public static final List<String> CATEGORIES = Arrays.asList(
            FOOD, "Groceries", "Transport", "Fuel", "Shopping", "Bills & Recharge", "Entertainment",
            "Health", "Rent & Home", "Education", "Travel", "Transfers", "Other", UNCATEGORISED);

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    private static void rule(String category, String... keywords) {
        StringBuilder sb = new StringBuilder("\\b(?:");
        for (int i = 0; i < keywords.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(keywords[i]));
        }
        RULES.put(category, Pattern.compile(sb.append(')').toString()));
    }

    static {
        // Order matters: first match wins ("swiggy instamart" is groceries, not dining).
        rule("Groceries", "bigbasket", "big basket", "blinkit", "zepto", "instamart", "dmart", "grocer",
                "kirana", "supermarket", "milk", "vegetable", "fruit", "jiomart");
        rule(FOOD, "swiggy", "zomato", "domino", "pizza", "restaurant", "cafe", "kfc", "mcdonald",
                "burger", "biryani", "bakery", "dhaba", "chai", "coffee", "starbucks", "lunch", "dinner",
                "breakfast", "snack", "eatclub", "food");
        rule("Fuel", "petrol", "diesel", "fuel", "hpcl", "bpcl", "iocl", "indian oil", "indianoil",
                "bharat petroleum", "shell");
        rule("Travel", "irctc", "redbus", "makemytrip", "goibibo", "indigo", "air india", "flight",
                "hotel", "oyo", "airbnb", "cleartrip", "ixigo");
        rule("Transport", "uber", "ola", "rapido", "metro", "namma yatri", "auto", "cab", "taxi", "bus",
                "parking", "toll", "fastag");
        rule("Entertainment", "netflix", "hotstar", "spotify", "prime video", "bookmyshow", "pvr", "inox",
                "youtube", "movie", "game", "steam", "playstation");
        rule("Bills & Recharge", "airtel", "jio", "vodafone", "bsnl", "electricity", "bescom", "recharge",
                "broadband", "fibernet", "dth", "tata play", "gas", "water bill", "bill", "insurance", "lic");
        rule("Health", "pharmacy", "pharma", "medical", "medplus", "apollo", "1mg", "hospital", "clinic",
                "doctor", "diagnostic", "lab", "gym", "cult");
        rule("Rent & Home", "rent", "nobroker", "maintenance", "society", "urban company", "urbanclap",
                "furniture", "ikea");
        rule("Education", "udemy", "coursera", "school", "college", "tuition", "course", "book", "exam");
        rule("Shopping", "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "decathlon",
                "croma", "reliance digital", "mall", "store", "shop");
    }

    public static String guess(String merchant, String note) {
        String text = ((merchant == null ? "" : merchant) + " " + (note == null ? "" : note))
                .toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Pattern> e : RULES.entrySet()) {
            if (e.getValue().matcher(text).find()) return e.getKey();
        }
        return UNCATEGORISED;
    }
}
