package com.timereport.mail.adapter.out.persistence;

import com.timereport.mail.application.port.out.MailTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class JdbcMailTransaction implements MailTransaction {
    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void execute(Runnable work) {
        work.run();
    }
}
