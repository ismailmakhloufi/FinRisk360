package tn.finrisk.transactionservice.config;

import java.util.List;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * Configuration de la faade SOAP/XML (exigence M2).
 *  - le XSD valide chaque requete ET chaque reponse AVANT/apres l'endpoint
 *  - le WSDL est genere automatiquement a partir du XSD : /ws/<nomDuBean>.wsdl
 *  - @EnableWs declare les mappings @Endpoint/intercepteurs ; comme il fournit
 *    WsConfigurationSupport, Spring Boot n'enregistre plus le MessageDispatcherServlet
 *    (auto-config "back off") : on le declare nous-memes sur /ws/*
 * La faade REST n'a rien de tout cela : c'est le 2e "visage" de TransactionService.
 */
@Configuration
@EnableWs
public class WebServiceConfig implements WsConfigurer {

    /** Namespace XML commun au XSD, aux classes JAXB et a l'endpoint. */
    public static final String NAMESPACE = "http://finrisk.tn/transactions";

    /** Servlet Spring WS : les requetes POST SOAP de /ws/ atterrissent ici. */
    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServletRegistration(
            ApplicationContext context) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(context);
        servlet.setTransformWsdlLocations(true);
        ServletRegistrationBean<MessageDispatcherServlet> registration =
                new ServletRegistrationBean<>(servlet, "/ws/*");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public XsdSchema transactionSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/transaction.xsd"));
    }

    /** Genere /ws/transactionWsdlDefinition.wsdl depuis le XSD. */
    @Bean
    public DefaultWsdl11Definition transactionWsdlDefinition(XsdSchema transactionSchema) {
        DefaultWsdl11Definition definition = new DefaultWsdl11Definition();
        definition.setPortTypeName("TransactionPort");
        definition.setLocationUri("/ws");
        definition.setTargetNamespace(NAMESPACE);
        definition.setSchema(transactionSchema);
        return definition;
    }

    /** Serialisation XML <-> objets Java (JAXB, via Jaxb2Marshaller de Spring OXM). */
    @Bean
    public Jaxb2Marshaller jaxb2Marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setPackagesToScan("tn.finrisk.transactionservice.web.soap");
        return marshaller;
    }

    /** Validation des charges utiles contre le XSD (requetes ET reponses). */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        PayloadValidatingInterceptor validatingInterceptor = new PayloadValidatingInterceptor();
        validatingInterceptor.setXsdSchema(transactionSchema());
        validatingInterceptor.setValidateRequest(true);
        validatingInterceptor.setValidateResponse(true);
        interceptors.add(validatingInterceptor);
    }
}
