package com.boxcinema.dto;

public class DateTabDto {

    private String dateStr;
    private String dayNumber;
    private String monthAndDayOfWeek;
    private boolean active;

    public DateTabDto() {
    }

    public DateTabDto(String dateStr, String dayNumber, String monthAndDayOfWeek, boolean active) {
        this.dateStr = dateStr;
        this.dayNumber = dayNumber;
        this.monthAndDayOfWeek = monthAndDayOfWeek;
        this.active = active;
    }

    public String getDateStr() {
        return dateStr;
    }

    public void setDateStr(String dateStr) {
        this.dateStr = dateStr;
    }

    public String getDayNumber() {
        return dayNumber;
    }

    public void setDayNumber(String dayNumber) {
        this.dayNumber = dayNumber;
    }

    public String getMonthAndDayOfWeek() {
        return monthAndDayOfWeek;
    }

    public void setMonthAndDayOfWeek(String monthAndDayOfWeek) {
        this.monthAndDayOfWeek = monthAndDayOfWeek;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

