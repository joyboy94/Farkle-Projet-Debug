package model;

import java.util.Random;

public class Dice {
    private int value;

    public Dice() {
        roll();
    }

    public Dice(int value) {
        this.value = value;
    }

    public void roll() {
        this.value = new Random().nextInt(6) + 1;
    }

    public int getValue() {
        return value;
    }
public void setValue(int value) {
    this.value = value;
}}

