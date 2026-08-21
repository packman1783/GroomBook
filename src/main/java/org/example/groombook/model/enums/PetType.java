package org.example.groombook.model.enums;

public enum PetType {
    DOG("Собака", "🐕"),
    CAT("Кошка", "🐈"),
    OTHER("Другое", "🐾");

    private final String label;
    private final String emoji;

    PetType(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }

    public String getLabel() {
        return label;
    }

    public String getEmoji() {
        return emoji;
    }

    public String getFullDisplay() {
        return emoji + " " + label;
    }

    @Override
    public String toString() {
        return label;
    }
}
