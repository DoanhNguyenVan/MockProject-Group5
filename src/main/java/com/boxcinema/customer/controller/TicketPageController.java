package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.response.BookingHistoryDetailDto;
import com.boxcinema.customer.dto.response.BookingHistoryItemDto;
import com.boxcinema.common.dto.BookingTicketViewDto;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.customer.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Set;

@Controller
public class TicketPageController {

    private final TicketService ticketService;

    public TicketPageController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping("/booking/history")
    public String bookingHistory(Model model, HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        List<BookingHistoryItemDto> historyList = ticketService.getBookingHistory(userId);
        model.addAttribute("historyList", historyList);
        model.addAttribute("activePage", "history");
        return "customer/booking-history";
    }

    @GetMapping("/booking/history/{bookingId}")
    public String bookingDetail(
            @PathVariable Long bookingId,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        Set<Long> guestIds = SecurityContextUtils.extractGuestSessionBookingIds(request);
        try {
            BookingHistoryDetailDto detail = ticketService.getBookingDetail(bookingId, userId, guestIds);
            model.addAttribute("detail", detail);
            model.addAttribute("bookingId", bookingId);
            model.addAttribute("activePage", "history");
            return "customer/booking-detail";
        } catch (AccessDeniedException ex) {
            if (userId == null) {
                return "redirect:/login";
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            model.addAttribute("errorMessage", ex.getMessage());
            return "error";
        } catch (Exception ex) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("errorMessage", ex.getMessage());
            return "error";
        }
    }

    @GetMapping("/booking/ticket/{bookingId}")
    public String viewTicket(
            @PathVariable Long bookingId,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        Set<Long> guestIds = SecurityContextUtils.extractGuestSessionBookingIds(request);
        try {
            BookingTicketViewDto ticketView = ticketService.getTicketView(bookingId, userId, guestIds);
            model.addAttribute("ticket", ticketView);
            model.addAttribute("bookingId", bookingId);
            return "customer/ticket";
        } catch (AccessDeniedException ex) {
            if (userId == null) {
                return "redirect:/login";
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            model.addAttribute("errorMessage", ex.getMessage());
            return "error";
        } catch (Exception ex) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("errorMessage", ex.getMessage());
            return "error";
        }
    }
}
