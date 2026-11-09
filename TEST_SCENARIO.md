# BOX CINEMA test scenarios

This document is for the test scenarios, which are used to fulfill Fundamential Requirements for the project.

## Account and Discovery

### Login
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-LOG-001 | FR-01 | Register with valid information | Positive | Valid name, unique email, and valid password | The account is created successfully.  | [ ] |
| TS-LOG-002 | FR-01 | Register with an email that already exists | Negative | An email address that is already registered | The system rejects the duplicate email and displays an appropriate error message.  | [ ] |
| TS-LOG-003 | FR-01 | Register with required fields left empty | Negative | Leave one or more required fields empty | The system displays validation errors for the required fields.  | [ ] |
| TS-LOG-004 | FR-01 | Register with improper email | Negative | Enter an incorrectly formatted email address | The system displays a validation error for the email field.  | [ ] |
| TS-LOG-005 | FR-01 | Register with password less than 6 characters | Negative | Enter a password containing fewer than 6 characters | The system displays a validation error for the password field.  | [ ] |
| TS-LOG-006 | FR-01 | Log in with a correct email and password | Positive | Registered email and correct password | The user logs in successfully and is redirected to the Home Page.  | [ ] |
| TS-LOG-007 | FR-01 | Log in with an incorrect email or password | Negative | Incorrect email or incorrect password | The system displays an authentication error.  | [ ] |
| TS-LOG-008 | FR-01 | Log in with an empty email or password | Negative | Leave the email or password field empty | The system displays a validation message requesting the missing information.  | [ ] |
| TS-LOG-009 | FR-01 | Enter incorrect login credentials five consecutive times | Negative | Enter incorrect login credentials 5 consecutive times | The system locks the account for 30 minutes and displays a warning message.  | [ ] |
| TS-LOG-010 | FR-01 | Reset the user's password | Positive | Submit a valid password reset request with the required information | The user can complete the password reset process successfully.  | [ ] |

### Movie Discovery
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-MOV-01 | FR-02 | View the movie list | Positive | Open the movie listing page | The system displays the available movies.  | [ ] |
| TS-MOV-02 | FR-02 | Search for an existing movie | Positive | Enter the name of an existing movie | The system displays movies matching the search keyword.  | [ ] |
| TS-MOV-03 | FR-02 | Search for a nonexistent movie | Negative | Enter a movie name that does not exist | The system displays a "No search results" message.  | [ ] |
| TS-MOV-04 | FR-02 | Search with an empty keyword | Negative | Leave the search field empty and click Search | The system handles the empty search appropriately without errors.  | [ ] |
| TS-MOV-05 | FR-02 | Filter movies by genre | Positive | Select an available movie genre | The system displays movies belonging to the selected genre.  | [ ] |
| TS-MOV-06 | FR-02 | Filter movies with no matching results | Negative | Select a genre with no available movies | The system displays an appropriate no-results message.  | [ ] |
| TS-MOV-07 | FR-03 | View movie details | Positive | Select an available movie | The system displays the movie's details.  | [ ] |
| TS-MOV-08 | FR-03 | View showtimes by cinema and date | Positive | Select a movie, cinema, and date | The system displays the corresponding showtimes.  | [ ] |

## Booking and Payment
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-BOOK-001 | FR-04 | Select available seats for a showtime | Positive | Select one or more available seats for a selected showtime | The system selects the seats and temporarily holds them for the user.  | [ ] |
| TS-BOOK-002 | FR-04 | Attempt to select a seat that is already booked or held by another user | Negative | Select a seat that is unavailable for the selected showtime | The system prevents the user from selecting the unavailable seat.  | [ ] |
| TS-BOOK-003 | FR-04 | Keep selected seats for more than 10 minutes without completing the booking | Negative | Select seats and wait longer than the 10-minute hold period | The system releases the held seats so they become available to other users.  | [ ] |
| TS-BOOK-004 | FR-05 | Attempt to book tickets without logging in | Negative | Access the booking process and attempt to book tickets without logging in | The system prevents the user from proceeding with the booking and requests the user to log in.  | [ ] |
| TS-BOOK-005 | FR-05 | Book tickets with valid selected seats | Positive | Confirm a booking with selected available seats | The system creates the booking successfully.  | [ ] |
| TS-BOOK-006 | FR-05 | Add a combo to the booking | Positive | Select an available food or drink combo during booking | The system adds the selected combo to the booking and updates the total amount.  | [ ] |
| TS-BOOK-007 | FR-05 | Apply a valid voucher to the booking | Positive | Enter a valid voucher code that meets its conditions | The system applies the voucher and updates the booking total.  | [ ] |
| TS-BOOK-008 | FR-05 | Apply an invalid or ineligible voucher to the booking | Negative | Enter an invalid voucher code or a code whose conditions are not met | The system rejects the voucher and informs the user that it cannot be applied.  | [ ] |
| TS-BOOK-009 | FR-06 | Complete a booking payment successfully via VNPay | Positive | Complete the payment using a valid VNPay payment flow | The system records the successful payment and confirms the booking.  | [ ] |
| TS-BOOK-010 | FR-06 | Payment through VNPay is unsuccessful | Negative | Attempt to pay through VNPay but the payment is unsuccessful | The system does not mark the booking as paid and displays the payment result.  | [ ] |
| TS-BOOK-011 | FR-06 | Receive the QR e-ticket by email after successful payment | Positive | Complete a booking payment successfully with a valid email address | The system sends the QR e-ticket to the user's email address.  | [ ] |

### History
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-HIS-001 | FR-07 | View booking history while logged in | Positive | Log in and open the booking history page | The system displays the bookings associated with the logged-in user's account.  | [ ] |
| TS-HIS-002 | FR-07 | View booking history when there are no previous bookings | Positive | Log in with an account that has no booking records and open the booking history page | The system displays an appropriate empty-state message.  | [ ] |
| TS-HIS-003 | FR-07 | Cancel a booking that meets the cancellation policy | Positive | Select an eligible booking and submit a cancellation request | The system processes the cancellation according to the cancellation policy and updates the booking status.  | [ ] |
| TS-HIS-004 | FR-07 | Attempt to cancel a booking that does not meet the cancellation policy | Negative | Select a booking that is not eligible for cancellation and submit a cancellation request | The system rejects the cancellation request and displays an appropriate message.  | [ ] |

## Cinema Operation
### Counter 
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-CTR-001 | FR-08 | Staff accesses the counter sales function | Positive | Log in with a Staff account and open the counter sales function | The system allows Staff to access the counter sales function.  | [ ] |
| TS-CTR-002 | FR-08 | Guest attempts to access the counter sales function | Negative | Access the counter sales function without logging in | The system denies access to the counter sales function.  | [ ] |
| TS-CTR-003 | FR-08 | Customer attempts to access the counter sales function | Negative | Log in with a Customer account and attempt to open the counter sales function | The system denies access to the counter sales function.  | [ ] |
| TS-CTR-004 | FR-08 | Staff creates a counter sale with valid information | Positive | Log in as Staff, enter valid ticket and payment information, and submit the counter sale | The system records the counter sale successfully.  | [ ] |
| TS-CTR-005 | FR-08 | Staff submits a counter sale with missing required information | Negative | Log in as Staff and submit the counter sale with one or more required fields missing | The system rejects the sale and displays validation messages.  | [ ] |
| TS-CTR-006 | FR-08 | Customer checks in using a valid ticket QR code | Positive | Log in as Customer and scan a valid QR code associated with the customer's ticket | The system verifies the ticket and records the check-in successfully.  | [ ] |
| TS-CTR-007 | FR-08 | Customer attempts to check in using an invalid QR code | Negative | Log in as Customer and scan an invalid QR code | The system rejects the QR code and does not record the check-in.  | [ ] |
| TS-CTR-008 | FR-08 | Customer attempts to check in using an already-used QR code | Negative | Log in as Customer and scan a QR code that has already been used for check-in | The system rejects the QR code and prevents duplicate check-in.  | [ ] |

### Manager
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-MNG-001 | FR-09 | Manager accesses movie and genre management | Positive | Log in with a Manager account and open movie and genre management | The system allows the Manager to access movie and genre management.  | [ ] |
| TS-MNG-002 | FR-09 | Non-Manager attempts to access movie and genre management | Negative | Log in with a Guest, Customer, or Staff account and attempt to open movie and genre management | The system denies access to movie and genre management.  | [ ] |
| TS-MNG-003 | FR-09 | Manager creates a movie with valid information | Positive | Log in as Manager, enter valid movie information, and submit | The system creates the movie successfully.  | [ ] |
| TS-MNG-004 | FR-09 | Manager creates a movie with missing required information | Negative | Log in as Manager and submit the movie form with required fields missing | The system rejects the submission and displays validation messages.  | [ ] |
| TS-MNG-005 | FR-09 | Manager creates a new movie genre | Positive | Log in as Manager, enter a valid genre name, and save | The system creates the genre successfully.  | [ ] |
| TS-MNG-006 | FR-10 | Manager accesses cinema, room, and seat map management | Positive | Log in with a Manager account and open cinema, room, and seat map management | The system allows the Manager to access these management functions.  | [ ] |
| TS-MNG-007 | FR-10 | Non-Manager attempts to access cinema, room, and seat map management | Negative | Log in with a Guest, Customer, or Staff account and attempt to open cinema, room, and seat map management | The system denies access to these management functions.  | [ ] |
| TS-MNG-008 | FR-10 | Manager creates a cinema with valid information | Positive | Log in as Manager, enter valid cinema information, and save | The system creates the cinema successfully.  | [ ] |
| TS-MNG-009 | FR-10 | Manager creates a room with valid information | Positive | Log in as Manager, select a cinema, enter valid room information, and save | The system creates the room successfully under the selected cinema.  | [ ] |
| TS-MNG-010 | FR-10 | Manager configures a valid seat map for a room | Positive | Log in as Manager, select a room, configure its seat layout, and save | The system saves the seat map successfully.  | [ ] |
| TS-MNG-011 | FR-10 | Manager submits an invalid seat map configuration | Negative | Log in as Manager and submit a seat map with invalid or incomplete configuration data | The system rejects the configuration and displays an appropriate validation message.  | [ ] |
| TS-MNG-012 | FR-11 | Manager accesses showtime and ticket price management | Positive | Log in with a Manager account and open showtime and ticket price management | The system allows the Manager to access showtime and ticket price management.  | [ ] |
| TS-MNG-013 | FR-11 | Non-Manager attempts to access showtime and ticket price management | Negative | Log in with a Guest, Customer, or Staff account and attempt to open showtime and ticket price management | The system denies access to showtime and ticket price management.  | [ ] |
| TS-MNG-014 | FR-11 | Manager creates a showtime with valid information | Positive | Log in as Manager and submit a showtime with a valid movie, room, date, and time | The system creates the showtime successfully.  | [ ] |
| TS-MNG-015 | FR-11 | Manager attempts to create overlapping showtimes in the same room | Negative | Log in as Manager and submit a showtime that overlaps with an existing showtime in the same room | The system rejects the overlapping showtime.  | [ ] |
| TS-MNG-016 | FR-11 | Manager updates ticket prices with valid values | Positive | Log in as Manager, select a showtime, enter valid ticket prices, and save | The system updates the ticket prices successfully.  | [ ] |
| TS-MNG-017 | FR-11 | Manager submits invalid ticket prices | Negative | Log in as Manager and submit a negative or otherwise invalid ticket price | The system rejects the invalid price and displays a validation message.  | [ ] |

## Promotion and Reporting
### Promotion
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-PRO-001 | FR-12 | Manager accesses voucher, combo, staff, and role management | Positive | Log in with a Manager account and open the management functions | The system allows the Manager to access the permitted management functions.  | [ ] |
| TS-PRO-002 | FR-12 | Non-Manager attempts to access voucher, combo, staff, or role management | Negative | Log in with a Guest, Customer, or Staff account and attempt to open a restricted management function | The system denies access to the restricted management function.  | [ ] |
| TS-PRO-003 | FR-12 | Manager creates a voucher with valid information | Positive | Log in as Manager, enter valid voucher information, and save | The system creates the voucher successfully.  | [ ] |
| TS-PRO-004 | FR-12 | Manager creates a voucher with invalid or missing information | Negative | Log in as Manager and submit a voucher form with invalid or missing required information | The system rejects the voucher and displays validation messages.  | [ ] |
| TS-PRO-005 | FR-12 | Manager creates a combo with valid information | Positive | Log in as Manager, enter valid combo information and its products, and save | The system creates the combo successfully.  | [ ] |
| TS-PRO-006 | FR-12 | Manager creates a staff account with valid information and assigns a role | Positive | Log in as Manager, enter valid staff information, select an available role, and save | The system creates the staff account and assigns the selected role.  | [ ] |
| TS-PRO-007 | FR-12 | Manager attempts to create a staff account with missing required information | Negative | Log in as Manager and submit the staff form with required fields missing | The system rejects the submission and displays validation messages.  | [ ] |
| TS-PRO-008 | FR-12 | Manager updates a staff member's assigned role | Positive | Log in as Manager, select an existing staff account, change its role, and save | The system updates the staff member's role successfully.  | [ ] |

### Dashboard
| Test Scenario ID | Requirement ID | Test Scenario | Test Type | Input | Expected Output  | Status |
|---|---|---|---|---|---|---|
| TS-DASH-001 | FR-13 | Manager accesses the dashboard | Positive | Log in with a Manager account and open the dashboard | The system displays the dashboard.  | [ ] |
| TS-DASH-002 | FR-13 | Non-Manager attempts to access the dashboard | Negative | Log in with a Guest, Customer, or Staff account and attempt to open the dashboard | The system denies access to the dashboard.  | [ ] |
| TS-DASH-003 | FR-13 | Manager views revenue statistics | Positive | Log in as Manager and view the revenue section of the dashboard | The system displays the available revenue statistics.  | [ ] |
| TS-DASH-004 | FR-13 | Manager views ticket statistics | Positive | Log in as Manager and view the ticket statistics section of the dashboard | The system displays the available ticket statistics.  | [ ] |
| TS-DASH-005 | FR-13 | Manager views top movie statistics | Positive | Log in as Manager and view the top movies section of the dashboard | The system displays the top movie statistics.  | [ ] |
