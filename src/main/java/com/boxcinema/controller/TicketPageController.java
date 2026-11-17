package com.boxcinema.controller;

import com.boxcinema.dto.BookingHistoryDetailDto;
import com.boxcinema.dto.BookingHistoryItemDto;
import com.boxcinema.dto.BookingTicketViewDto;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.TicketService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Controller
public class TicketPageController {

    private final TicketService ticketService;

    public TicketPageController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    private Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof BoxCinemaUserDetails details) {
            if (details.getUserSummary() != null && details.getUserSummary().getUserId() != null) {
                return details.getUserSummary().getUserId();
            }
        }
        return null;
    }

    @GetMapping("/booking/history")
    public String bookingHistory(Model model) {
        Long userId = resolveCurrentUserId();
        List<BookingHistoryItemDto> historyList = ticketService.getBookingHistory(userId);
        model.addAttribute("historyList", historyList);
        model.addAttribute("activePage", "history");
        return "customer/booking-history";
    }

    @GetMapping("/booking/history/{bookingId}")
    public String bookingDetail(@PathVariable Long bookingId, Model model) {
        Long userId = resolveCurrentUserId();
        try {
            BookingHistoryDetailDto detail = ticketService.getBookingDetail(bookingId, userId);
            model.addAttribute("detail", detail);
            model.addAttribute("bookingId", bookingId);
            model.addAttribute("activePage", "history");
            return "customer/booking-detail";
        } catch (Exception ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            return "error";
        }
    }

    @GetMapping("/booking/ticket/{bookingId}")
    public String viewTicket(@PathVariable Long bookingId, Model model) {
        Long userId = resolveCurrentUserId();
        try {
            BookingTicketViewDto ticketView = ticketService.getTicketView(bookingId, userId);
            model.addAttribute("ticket", ticketView);
            model.addAttribute("bookingId", bookingId);
            return "customer/ticket";
        } catch (Exception ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            return "error";
        }
    }
}
