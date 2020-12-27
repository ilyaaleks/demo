package my.bookshop.config;

import com.sap.xs.audit.api.exception.AuditLogException;
import com.sap.xs.audit.api.v2.AuditLogMessageFactory;
import com.sap.xs.audit.client.impl.v2.AuditLogMessageFactoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditLoggerConf {
    @Bean
    public AuditLogMessageFactory auditLogMessageFactory() throws AuditLogException {
        return new AuditLogMessageFactoryImpl();
    }
}
