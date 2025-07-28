package io.swagger.model;

public class MessageEvent {
    public String message;
    public String color; // exemple : "#FFD700" ou "gold" ou "red"
    public MessageEvent(String message, String color) {
        this.message = message;
        this.color = color;
    }
}
