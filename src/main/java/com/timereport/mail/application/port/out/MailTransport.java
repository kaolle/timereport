package com.timereport.mail.application.port.out;

import com.timereport.mail.domain.MailMessage;
import com.timereport.mail.domain.MailReference;

/** Successful return means provider acceptance, not inbox delivery. */
public interface MailTransport {
    void send(MailReference reference, MailMessage message);
}
