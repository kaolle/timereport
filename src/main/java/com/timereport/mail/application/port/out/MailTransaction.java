package com.timereport.mail.application.port.out;

public interface MailTransaction {
    void execute(Runnable work);
}
