package com.boxcinema.staff.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class StaffPosShiftSummaryDto {
    private String date;
    private String staffName;
    private BigDecimal totalRevenue = BigDecimal.ZERO;
    private BigDecimal cashRevenue = BigDecimal.ZERO;
    private BigDecimal cardRevenue = BigDecimal.ZERO;
    private BigDecimal payOsRevenue = BigDecimal.ZERO;
    private int orderCount;
    private int ticketCount;
    private List<StaffPosOrderSummaryDto> orders = new ArrayList<>();

    public StaffPosShiftSummaryDto() {}

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }

    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }

    public BigDecimal getCashRevenue() { return cashRevenue; }
    public void setCashRevenue(BigDecimal cashRevenue) { this.cashRevenue = cashRevenue; }

    public BigDecimal getCardRevenue() { return cardRevenue; }
    public void setCardRevenue(BigDecimal cardRevenue) { this.cardRevenue = cardRevenue; }

    public BigDecimal getPayOsRevenue() { return payOsRevenue; }
    public void setPayOsRevenue(BigDecimal payOsRevenue) { this.payOsRevenue = payOsRevenue; }

    public int getOrderCount() { return orderCount; }
    public void setOrderCount(int orderCount) { this.orderCount = orderCount; }

    public int getTicketCount() { return ticketCount; }
    public void setTicketCount(int ticketCount) { this.ticketCount = ticketCount; }

    public List<StaffPosOrderSummaryDto> getOrders() { return orders; }
    public void setOrders(List<StaffPosOrderSummaryDto> orders) { this.orders = orders; }
}
