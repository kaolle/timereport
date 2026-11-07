package com.timereport.mail.application.port.out;

import com.timereport.mail.domain.MailReference;
import com.timereport.mail.domain.MailState;
import java.time.Instant;

public interface MailDeliveryEvents {
    void completed(MailReference reference, MailState state, Instant completedAt);
}
