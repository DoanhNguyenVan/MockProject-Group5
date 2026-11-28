package com.boxcinema.common.constant;

public final class SessionConstants {

    private SessionConstants() {
    }

    public static final String CURRENT_USER = "currentUser";
    public static final String REDIRECT_AFTER_LOGIN = "REDIRECT_AFTER_LOGIN";
    public static final String BOX_HOLD_SESSION_ID = "BOX_HOLD_SESSION_ID";
    public static final String SESSION_GUEST_BOOKING_IDS = "BOX_GUEST_BOOKING_IDS";

    public static final String SESSION_OAUTH_STATE = "BOX_OAUTH_STATE";
    public static final String SESSION_OAUTH_NONCE = "BOX_OAUTH_NONCE";
    public static final String SESSION_OAUTH_CODE_VERIFIER = "BOX_OAUTH_CODE_VERIFIER";
    public static final String SESSION_OAUTH_FLOW = "BOX_OAUTH_FLOW";
    public static final String SESSION_OAUTH_EXPIRES_AT_MS = "BOX_OAUTH_EXPIRES_AT_MS";

    public static final String SESSION_LINK_OAUTH_STATE = "BOX_LINK_OAUTH_STATE";
    public static final String SESSION_LINK_OAUTH_NONCE = "BOX_LINK_OAUTH_NONCE";
    public static final String SESSION_LINK_OAUTH_VERIFIER = "BOX_LINK_OAUTH_VERIFIER";
    public static final String SESSION_LINK_OAUTH_USER_ID = "BOX_LINK_OAUTH_USER_ID";
    public static final String SESSION_LINK_OAUTH_EXPIRES_AT_MS = "BOX_LINK_OAUTH_EXPIRES_AT_MS";
    public static final String SESSION_LINK_OAUTH_REDIRECT_URI = "BOX_LINK_OAUTH_REDIRECT_URI";

    public static final String SESSION_LINK_TX_TOKEN = "BOX_LINK_TX_TOKEN";
    public static final String SESSION_LINK_TX_USER_ID = "BOX_LINK_TX_USER_ID";
    public static final String SESSION_LINK_TX_EXPIRES_AT_MS = "BOX_LINK_TX_EXPIRES_AT_MS";
    public static final String SESSION_LINK_TX_USED = "BOX_LINK_TX_USED";

    public static final String SESSION_SETPW_OAUTH_STATE = "BOX_SETPW_OAUTH_STATE";
    public static final String SESSION_SETPW_OAUTH_NONCE = "BOX_SETPW_OAUTH_NONCE";
    public static final String SESSION_SETPW_OAUTH_VERIFIER = "BOX_SETPW_OAUTH_VERIFIER";
    public static final String SESSION_SETPW_OAUTH_USER_ID = "BOX_SETPW_OAUTH_USER_ID";
    public static final String SESSION_SETPW_OAUTH_EXPIRES_AT_MS = "BOX_SETPW_OAUTH_EXPIRES_AT_MS";
    public static final String SESSION_SETPW_OAUTH_REDIRECT_URI = "BOX_SETPW_OAUTH_REDIRECT_URI";
    public static final String SESSION_SETPW_REAUTH_USER_ID = "BOX_SET_PW_REAUTH_USER_ID";
    public static final String SESSION_SETPW_REAUTH_EXPIRES_AT_MS = "BOX_SET_PW_REAUTH_EXPIRES_AT_MS";

    public static final String SESSION_REAUTH_USER_ID = "BOX_REAUTH_USER_ID";
    public static final String SESSION_REAUTH_AT_MS = "BOX_REAUTH_VERIFIED_AT_MS";

    public static final String SESSION_PENDING_GOOGLE_SUB = "BOX_PENDING_GOOGLE_SUB";
    public static final String SESSION_PENDING_GOOGLE_EMAIL = "BOX_PENDING_GOOGLE_EMAIL";
    public static final String SESSION_PENDING_GOOGLE_NAME = "BOX_PENDING_GOOGLE_NAME";
    public static final String SESSION_PENDING_GOOGLE_VERIFIED = "BOX_PENDING_GOOGLE_VERIFIED";
    public static final String SESSION_PENDING_GOOGLE_EXPIRES_AT_MS = "BOX_PENDING_GOOGLE_EXPIRES_AT_MS";
    public static final String SESSION_PENDING_GOOGLE_MODE = "BOX_PENDING_GOOGLE_MODE";
    public static final String SESSION_PENDING_GOOGLE_TARGET_EMAIL = "BOX_PENDING_GOOGLE_TARGET_EMAIL";
    public static final String SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID = "BOX_PENDING_GOOGLE_OTP_CHALLENGE_ID";

    public static final long REAUTH_VALIDITY_MS = 5 * 60 * 1000L;
    public static final long OAUTH_STATE_TTL_MS = 5 * 60 * 1000L;
    public static final long PENDING_GOOGLE_TTL_MS = 10 * 60 * 1000L;
}
