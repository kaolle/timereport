package com.timereport.mail.application.port.in;

import com.timereport.mail.domain.MailReference;

public interface DispatchMail {
    int dispatch();
    int dispatch(MailReference reference);
}
