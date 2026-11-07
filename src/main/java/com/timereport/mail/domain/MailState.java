package com.timereport.mail.domain;

public enum MailState {
    PENDING, SENDING, SENT, FAILED, EXPIRED, CANCELLED;

    public boolean terminal() {
        return this != PENDING && this != SENDING;
    }
}
