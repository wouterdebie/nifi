/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.security.x509.ocsp;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyStore;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import javax.servlet.http.HttpServletRequest;
import org.apache.nifi.framework.security.util.SslContextFactory;
import org.apache.nifi.web.security.x509.ocsp.OcspStatus.ValidationStatus;
import org.apache.nifi.web.security.x509.ocsp.OcspStatus.VerificationStatus;
import org.apache.nifi.util.FormatUtils;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.util.WebUtils;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.ocsp.BasicOCSPResp;
import org.bouncycastle.ocsp.CertificateID;
import org.bouncycastle.ocsp.CertificateStatus;
import org.bouncycastle.ocsp.OCSPException;
import org.bouncycastle.ocsp.OCSPReq;
import org.bouncycastle.ocsp.OCSPReqGenerator;
import org.bouncycastle.ocsp.OCSPResp;
import org.bouncycastle.ocsp.OCSPRespStatus;
import org.bouncycastle.ocsp.RevokedStatus;
import org.bouncycastle.ocsp.SingleResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OcspCertificateValidator {

    private static final Logger logger = LoggerFactory.getLogger(OcspCertificateValidator.class);

    private static final String HTTPS = "https";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String OCSP_REQUEST_CONTENT_TYPE = "application/ocsp-request";

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 10000;

    private URI validationAuthorityURI;
    private Client client;
    private Map<String, X509Certificate> trustedCAs;

    private LoadingCache<OcspRequest, OcspStatus> ocspCache;

    public OcspCertificateValidator(final NiFiProperties properties) {
        // get the responder url
        final String rawValidationAuthorityUrl = properties.getProperty(NiFiProperties.SECURITY_OCSP_RESPONDER_URL);

        // set properties when appropriate
        if (StringUtils.isNotBlank(rawValidationAuthorityUrl)) {
            try {
                // attempt to parse the specified va url
                validationAuthorityURI = URI.create(rawValidationAuthorityUrl);

                // connection detials
                final ClientConfig config = new DefaultClientConfig();
                config.getProperties().put(ClientConfig.PROPERTY_READ_TIMEOUT, READ_TIMEOUT);
                config.getProperties().put(ClientConfig.PROPERTY_CONNECT_TIMEOUT, CONNECT_TIMEOUT);

                // initialize the client
                if (HTTPS.equalsIgnoreCase(validationAuthorityURI.getScheme())) {
                    client = WebUtils.createClient(config, SslContextFactory.createSslContext(properties));
                } else {
                    client = WebUtils.createClient(config);
                }

                // get the trusted CAs
                trustedCAs = getTrustedCAs(properties);

                // consider the ocsp certificate is specified
                final X509Certificate ocspCertificate = getOcspCertificate(properties);
                if (ocspCertificate != null) {
                    trustedCAs.put(ocspCertificate.getSubjectX500Principal().getName(), ocspCertificate);
                }

                // determine how long to cache the ocsp responses for
                final String rawCacheDurationDuration = properties.getUserCredentialCacheDuration();
                final long cacheDurationMillis = FormatUtils.getTimeDuration(rawCacheDurationDuration, TimeUnit.MILLISECONDS);

                // build the ocsp cache
                ocspCache = CacheBuilder.newBuilder().expireAfterWrite(cacheDurationMillis, TimeUnit.MILLISECONDS).build(new CacheLoader<OcspRequest, OcspStatus>() {
                    @Override
                    public OcspStatus load(OcspRequest ocspRequest) throws Exception {
                        final String subjectDn = ocspRequest.getSubjectCertificate().getSubjectX500Principal().getName();

                        logger.info(String.format("Validating client certificate via OCSP: <%s>", subjectDn));
                        final OcspStatus ocspStatus = getOcspStatus(ocspRequest);
                        logger.info(String.format("Client certificate status for <%s>: %s", subjectDn, ocspStatus.toString()));

                        return ocspStatus;
                    }
                });
            } catch (final Exception e) {
                logger.error("Disabling OCSP certificate validation. Unable to load OCSP configuration: " + e, e);
                client = null;
            }
        }
    }

    /**
     * Loads the ocsp certificate if specified. Null otherwise.
     *
     * @param properties nifi properties
     * @return certificate
     */
    private X509Certificate getOcspCertificate(final NiFiProperties properties) {
        X509Certificate validationAuthorityCertificate = null;

        final String validationAuthorityCertificatePath = properties.getProperty(NiFiProperties.SECURITY_OCSP_RESPONDER_CERTIFICATE);
        if (StringUtils.isNotBlank(validationAuthorityCertificatePath)) {
            try (final FileInputStream fis = new FileInputStream(validationAuthorityCertificatePath)) {
                final CertificateFactory cf = CertificateFactory.getInstance("X.509");
                validationAuthorityCertificate = (X509Certificate) cf.generateCertificate(fis);
            } catch (final Exception e) {
                throw new IllegalStateException("Unable to load the validation authority certificate: " + e);
            }
        }

        return validationAuthorityCertificate;
    }

    /**
     * Loads the trusted certificate authorities according to the specified
     * properties.
     *
     * @param properties properties
     * @return map of certificate authorities
     */
    private Map<String, X509Certificate> getTrustedCAs(final NiFiProperties properties) {
        final Map<String, X509Certificate> certificateAuthorities = new HashMap<>();

        // get the path to the truststore
        final String truststorePath = properties.getProperty(NiFiProperties.SECURITY_TRUSTSTORE);
        if (truststorePath == null) {
            throw new IllegalArgumentException("The truststore path is required.");
        }

        // get the truststore password
        final char[] truststorePassword;
        final String rawTruststorePassword = properties.getProperty(NiFiProperties.SECURITY_TRUSTSTORE_PASSWD);
        if (rawTruststorePassword == null) {
            truststorePassword = new char[0];
        } else {
            truststorePassword = rawTruststorePassword.toCharArray();
        }

        // load the configured truststore
        try (final FileInputStream fis = new FileInputStream(truststorePath)) {
            final KeyStore truststore = KeyStore.getInstance(KeyStore.getDefaultType());
            truststore.load(fis, truststorePassword);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(truststore);

            // consider any certificates in the truststore as a trusted ca
            for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager) {
                    for (X509Certificate ca : ((X509TrustManager) trustManager).getAcceptedIssuers()) {
                        certificateAuthorities.put(ca.getSubjectX500Principal().getName(), ca);
                    }
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to load the configured truststore: " + e);
        }

        return certificateAuthorities;
    }

    /**
     * Validates the specified certificate using OCSP if configured.
     *
     * @param request http request
     * @throws CertificateStatusException ex
     */
    public void validate(final HttpServletRequest request) throws CertificateStatusException {
        final X509Certificate[] certificates = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");

        // only validate if configured to do so
        if (client != null && certificates != null && certificates.length > 0) {
            final X509Certificate subjectCertificate = getSubjectCertificate(certificates);
            final X509Certificate issuerCertificate = getIssuerCertificate(certificates);
            if (issuerCertificate == null) {
                throw new IllegalArgumentException(String.format("Unable to obtain certificate of issuer <%s> for the specified subject certificate <%s>.",
                        subjectCertificate.getIssuerX500Principal().getName(), subjectCertificate.getSubjectX500Principal().getName()));
            }

            // create the ocsp status key
            final OcspRequest ocspRequest = new OcspRequest(subjectCertificate, issuerCertificate);

            try {
                // determine the status and ensure it isn't verified as revoked
                final OcspStatus ocspStatus = ocspCache.getUnchecked(ocspRequest);

                // we only disallow when we have a verified response that states the certificate is revoked
                if (VerificationStatus.Verified.equals(ocspStatus.getVerificationStatus()) && ValidationStatus.Revoked.equals(ocspStatus.getValidationStatus())) {
                    throw new CertificateStatusException(String.format("Client certificate for <%s> is revoked according to the certificate authority.",
                            subjectCertificate.getSubjectX500Principal().getName()));
                }
            } catch (final UncheckedExecutionException uee) {
                logger.warn(String.format("Unable to validate client certificate via OCSP: <%s>", subjectCertificate.getSubjectX500Principal().getName()), uee.getCause());
            }
        }
    }

    /**
     * Gets the subject certificate.
     *
     * @param certificates certs
     * @return subject cert
     */
    private X509Certificate getSubjectCertificate(final X509Certificate[] certificates) {
        return certificates[0];
    }

    /**
     * Gets the issuer certificate.
     *
     * @param certificates certs
     * @return issuer cert
     */
    private X509Certificate getIssuerCertificate(final X509Certificate[] certificates) {
        if (certificates.length > 1) {
            return certificates[1];
        } else if (certificates.length == 1) {
            final X509Certificate subjectCertificate = getSubjectCertificate(certificates);
            final X500Principal issuerPrincipal = subjectCertificate.getIssuerX500Principal();
            return trustedCAs.get(issuerPrincipal.getName());
        } else {
            return null;
        }
    }

    /**
     * Gets the OCSP status for the specified subject and issuer certificates.
     *
     * @param ocspStatusKey status key
     * @return ocsp status
     */
    private OcspStatus getOcspStatus(final OcspRequest ocspStatusKey) {
        final X509Certificate subjectCertificate = ocspStatusKey.getSubjectCertificate();
        final X509Certificate issuerCertificate = ocspStatusKey.getIssuerCertificate();

        // initialize the default status
        final OcspStatus ocspStatus = new OcspStatus();
        ocspStatus.setVerificationStatus(VerificationStatus.Unknown);
        ocspStatus.setValidationStatus(ValidationStatus.Unknown);

        try {
            // prepare the request
            final BigInteger subjectSerialNumber = subjectCertificate.getSerialNumber();
            final CertificateID certificateId = new CertificateID(CertificateID.HASH_SHA1, issuerCertificate, subjectSerialNumber);

            // generate the request
            final OCSPReqGenerator requestGenerator = new OCSPReqGenerator();
            requestGenerator.addRequest(certificateId);
            final OCSPReq ocspRequest = requestGenerator.generate();

            // perform the request
            final WebResource resource = client.resource(validationAuthorityURI);
            final ClientResponse response = resource.header(CONTENT_TYPE_HEADER, OCSP_REQUEST_CONTENT_TYPE).post(ClientResponse.class, ocspRequest.getEncoded());

            // ensure the request was completed successfully
            if (ClientResponse.Status.OK.getStatusCode() != response.getStatusInfo().getStatusCode()) {
                logger.warn(String.format("OCSP request was unsuccessful (%s).", response.getStatus()));
                return ocspStatus;
            }

            // interpret the response
            OCSPResp ocspResponse = new OCSPResp(response.getEntityInputStream());

            // verify the response status
            switch (ocspResponse.getStatus()) {
                case OCSPRespStatus.SUCCESSFUL:
                    ocspStatus.setResponseStatus(OcspStatus.ResponseStatus.Successful);
                    break;
                case OCSPRespStatus.INTERNAL_ERROR:
                    ocspStatus.setResponseStatus(OcspStatus.ResponseStatus.InternalError);
                    break;
                case OCSPRespStatus.MALFORMED_REQUEST:
                    ocspStatus.setResponseStatus(OcspStatus.ResponseStatus.MalformedRequest);
                    break;
                case OCSPRespStatus.SIGREQUIRED:
                    ocspStatus.setResponseStatus(OcspStatus.ResponseStatus.SignatureRequired);
                    break;
                case OCSPRespStatus.TRY_LATER:
                    ocspStatus.setResponseStatus(OcspStatus.ResponseStatus.TryLater);
                    break;
                case OCSPRespStatus.UNAUTHORIZED:
                    ocspStatus.setResponseStatus(OcspStatus.ResponseStatus.Unauthorized);
                    break;
                default:
                    ocspStatus.setResponseStatus(OcspStatus.ResponseStatus.Unknown);
                    break;
            }

            // only proceed if the response was successful
            if (ocspResponse.getStatus() != OCSPRespStatus.SUCCESSFUL) {
                logger.warn(String.format("OCSP request was unsuccessful (%s).", ocspStatus.getResponseStatus().toString()));
                return ocspStatus;
            }

            // ensure the appropriate response object
            final Object ocspResponseObject = ocspResponse.getResponseObject();
            if (ocspResponseObject == null || !(ocspResponseObject instanceof BasicOCSPResp)) {
                logger.warn(String.format("Unexcepted OCSP response object: %s", ocspResponseObject));
                return ocspStatus;
            }

            // get the response object
            final BasicOCSPResp basicOcspResponse = (BasicOCSPResp) ocspResponse.getResponseObject();

            // attempt to locate the responder certificate
            final X509Certificate[] responderCertificates = basicOcspResponse.getCerts(null);
            if (responderCertificates.length != 1) {
                logger.warn(String.format("Unexcepted number of OCSP responder certificates: %s", responderCertificates.length));
                return ocspStatus;
            }

            // get the responder certificate
            final X509Certificate trustedResponderCertificate = getTrustedResponderCertificate(responderCertificates[0], issuerCertificate);
            if (trustedResponderCertificate != null) {
                // verify the response
                if (basicOcspResponse.verify(trustedResponderCertificate.getPublicKey(), null)) {
                    ocspStatus.setVerificationStatus(VerificationStatus.Verified);
                } else {
                    ocspStatus.setVerificationStatus(VerificationStatus.Unverified);
                }
            } else {
                ocspStatus.setVerificationStatus(VerificationStatus.Unverified);
            }

            // validate the response
            final SingleResp[] responses = basicOcspResponse.getResponses();
            for (SingleResp singleResponse : responses) {
                final CertificateID responseCertificateId = singleResponse.getCertID();
                final BigInteger responseSerialNumber = responseCertificateId.getSerialNumber();

                if (responseSerialNumber.equals(subjectSerialNumber)) {
                    Object certStatus = singleResponse.getCertStatus();

                    // interpret the certificate status
                    if (CertificateStatus.GOOD == certStatus) {
                        ocspStatus.setValidationStatus(ValidationStatus.Good);
                    } else if (certStatus instanceof RevokedStatus) {
                        ocspStatus.setValidationStatus(ValidationStatus.Revoked);
                    } else {
                        ocspStatus.setValidationStatus(ValidationStatus.Unknown);
                    }
                }
            }
        } catch (final OCSPException | IOException | UniformInterfaceException | ClientHandlerException | NoSuchProviderException e) {
            logger.error(e.getMessage(), e);
        }

        return ocspStatus;
    }

    /**
     * Gets the trusted responder certificate. The response contains the
     * responder certificate, however we cannot blindly trust it. Instead, we
     * use a configured trusted CA. If the responder certificate is a trusted
     * CA, then we can use it. If the responder certificate is not directly
     * trusted, we still may be able to trust it if it was issued by the same CA
     * that issued the subject certificate. Other various checks may be required
     * (this portion is currently not implemented).
     *
     * @param responderCertificate cert
     * @param issuerCertificate cert
     * @return cert
     */
    private X509Certificate getTrustedResponderCertificate(final X509Certificate responderCertificate, final X509Certificate issuerCertificate) {
        // look for the responder's certificate specifically
        if (trustedCAs.containsKey(responderCertificate.getSubjectX500Principal().getName())) {
            return trustedCAs.get(responderCertificate.getSubjectX500Principal().getName());
        }

        // if the responder certificate was issued by the same CA that issued the subject certificate we may be able to use that...
        if (responderCertificate.getIssuerX500Principal().equals(issuerCertificate.getSubjectX500Principal())) {
            // perform a number of verification steps... TODO... from sun.security.provider.certpath.OCSPResponse.java... currently incomplete...
//            try {
//                // ensure appropriate key usage
//                final List<String> keyUsage = responderCertificate.getExtendedKeyUsage();
//                if (keyUsage == null || !keyUsage.contains(KP_OCSP_SIGNING_OID)) {
//                    return null;
//                }
//
//                // ensure the certificate is valid
//                responderCertificate.checkValidity();
//
//                // verify the signature
//                responderCertificate.verify(issuerCertificate.getPublicKey());
//
//                return responderCertificate;
//            } catch (final CertificateException | NoSuchAlgorithmException | InvalidKeyException | NoSuchProviderException | SignatureException e) {
//                return null;
//            }
            return null;
        } else {
            return null;
        }
    }
}
