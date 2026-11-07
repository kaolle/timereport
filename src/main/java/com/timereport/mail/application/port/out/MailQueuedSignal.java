package com.timereport.mail.application.port.out;

import com.timereport.mail.domain.MailReference;

public interface MailQueuedSignal {
    void queued(MailReference reference);
}
