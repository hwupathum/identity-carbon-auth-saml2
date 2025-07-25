/*
 * Copyright (c) 2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.authenticator.saml2.sso;

import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.schema.impl.XSAnyImpl;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.security.AuthenticatorsConfiguration;
import org.wso2.carbon.core.services.authentication.CarbonServerAuthenticator;
import org.wso2.carbon.core.services.util.CarbonAuthenticationUtil;
import org.wso2.carbon.core.util.AnonymousSessionUtil;
import org.wso2.carbon.core.util.PermissionUpdateUtil;
import org.wso2.carbon.identity.authenticator.saml2.sso.common.SAML2SSOAuthenticatorConstants;
import org.wso2.carbon.identity.authenticator.saml2.sso.common.SAML2SSOUIAuthenticatorException;
import org.wso2.carbon.identity.authenticator.saml2.sso.dto.AuthnReqDTO;
import org.wso2.carbon.identity.authenticator.saml2.sso.internal.SAML2SSOAuthBEDataHolder;
import org.wso2.carbon.identity.authenticator.saml2.sso.util.Util;
import org.wso2.carbon.identity.central.log.mgt.utils.LoggerUtils;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.AuthenticationObserver;
import org.wso2.carbon.utils.ServerConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import static org.wso2.carbon.identity.authenticator.saml2.sso.common.Util.getDecryptedAssertion;

public class SAML2SSOAuthenticator implements CarbonServerAuthenticator {

    public static final Log log = LogFactory.getLog(SAML2SSOAuthenticator.class);
    private static final Log AUDIT_LOG = CarbonConstants.AUDIT_LOG;

    private SAML2SSOAuthBEDataHolder dataHolder = SAML2SSOAuthBEDataHolder.getInstance();

    private static final int DEFAULT_PRIORITY_LEVEL = 3;
    private static final String AUTHENTICATOR_NAME = SAML2SSOAuthenticatorBEConstants.SAML2_SSO_AUTHENTICATOR_NAME;
    private SecureRandom random = new SecureRandom();
    private int timeStampSkewInSeconds = 300;

    public boolean login(AuthnReqDTO authDto) {
        String username = null;
        String tenantDomain = null;
        String auditResult = SAML2SSOAuthenticatorConstants.AUDIT_RESULT_FAILED;

        HttpSession httpSession = getHttpSession();
        try {
            XMLObject xmlObject = Util.unmarshall(org.wso2.carbon.identity.authenticator.saml2.sso.common.Util.decode(authDto.getResponse()));

            username = org.wso2.carbon.identity.authenticator.saml2.sso.common.Util.getUsername(xmlObject);
            if (StringUtils.isBlank(username)) {
                log.error("Authentication Request is rejected. " +
                        "SAMLResponse does not contain the username of the subject.");
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, "", -1,
                        "SAML2 SSO Authentication", "SAMLResponse does not contain the username of the subject");
                // Unable to call #handleAuthenticationCompleted since there is no way to determine
                // tenantId without knowing the username.
                return false;
            }

            try {
                validateAssertionValidityPeriod(xmlObject);
            } catch (SAML2SSOAuthenticatorException e) {
                log.error("Authentication Request is rejected. " + e.getMessage());
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, username, -1,
                        "SAML2 SSO Authentication", e.getMessage());
                return false;
            }

            if (!validateAudienceRestrictionInXML(xmlObject)) {
                log.error("Authentication Request is rejected. SAMLResponse AudienceRestriction validation failed.");
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, username, -1,
                        "SAML2 SSO Authentication", "AudienceRestriction validation failed");
                return false;
            }


            RealmService realmService = dataHolder.getRealmService();
            tenantDomain = MultitenantUtils.getTenantDomain(username);
            int tenantId = realmService.getTenantManager().getTenantId(tenantDomain);
            boolean isSignatureValid = false;
            handleAuthenticationStarted(tenantId);

            isSignatureValid = validateSignature(xmlObject, tenantDomain);
            if (!isSignatureValid) {
                log.error("Authentication Request is rejected. Signature validation failed.");
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, username, tenantId, "SAML2 SSO Authentication",
                        "Invalid Signature");
                handleAuthenticationCompleted(tenantId, false);
                return false;
            }

            username = MultitenantUtils.getTenantAwareUsername(username);
            UserRealm realm = (UserRealm) realmService.getTenantUserRealm(tenantId);;
            // Authentication is done

            // Starting user provisioning
            provisionUser(username, realm, xmlObject);
            // End user provisioning

            // Starting Authorization

            PermissionUpdateUtil.updatePermissionTree(tenantId);
            boolean isAuthorized = false;
            if (realm != null) {
                isAuthorized = realm.getAuthorizationManager().isUserAuthorized(username,
                        "/permission/admin/login", CarbonConstants.UI_PERMISSION_ACTION);
            }
            if (isAuthorized) {
                UserCoreUtil.setDomainInThreadLocal(null);
                CarbonAuthenticationUtil.onSuccessAdminLogin(httpSession, username,
                        tenantId, tenantDomain, "SAML2 SSO Authentication");
                handleAuthenticationCompleted(tenantId, true);
                auditResult = SAML2SSOAuthenticatorConstants.AUDIT_RESULT_SUCCESS;
                return true;
            } else {
                log.error("Authentication Request is rejected. Authorization Failure.");
                CarbonAuthenticationUtil.onFailedAdminLogin(httpSession, username, tenantId,
                        "SAML2 SSO Authentication", "Authorization Failure");
                handleAuthenticationCompleted(tenantId, false);
                return false;
            }
        } catch (Exception e) {
            String msg = "System error while Authenticating/Authorizing User : " + e.getMessage();
            log.error(msg, e);
            return false;
        } finally {
            if (StringUtils.isNotBlank(username) && AUDIT_LOG.isInfoEnabled() && !LoggerUtils.isEnableV2AuditLogs()) {

                String auditInitiator = username + UserCoreConstants.TENANT_DOMAIN_COMBINER + tenantDomain;
                String auditData = "";

                AUDIT_LOG.info(String.format(SAML2SSOAuthenticatorConstants.AUDIT_MESSAGE,
                        auditInitiator, SAML2SSOAuthenticatorConstants.AUDIT_ACTION_LOGIN, AUTHENTICATOR_NAME,
                        auditData, auditResult));
            }
        }
    }

    private void handleAuthenticationStarted(int tenantId) {
        BundleContext bundleContext = dataHolder.getBundleContext();
        if (bundleContext != null) {
            ServiceTracker tracker = new ServiceTracker(bundleContext, AuthenticationObserver.class.getName(), null);
            tracker.open();
            Object[] services = tracker.getServices();
            if (services != null) {
                for (Object service : services) {
                    ((AuthenticationObserver) service).startedAuthentication(tenantId);
                }
            }
            tracker.close();
        }
    }

    private void handleAuthenticationCompleted(int tenantId, boolean isSuccessful) {
        BundleContext bundleContext = dataHolder.getBundleContext();
        if (bundleContext != null) {
            ServiceTracker tracker = new ServiceTracker(bundleContext, AuthenticationObserver.class.getName(), null);
            tracker.open();
            Object[] services = tracker.getServices();
            if (services != null) {
                for (Object service : services) {
                    ((AuthenticationObserver) service).completedAuthentication(
                            tenantId, isSuccessful);
                }
            }
            tracker.close();
        }
    }

    public void logout() {
        String loggedInUser;
        String delegatedBy;
        Date currentTime = Calendar.getInstance().getTime();
        SimpleDateFormat date = new SimpleDateFormat("'['yyyy-MM-dd HH:mm:ss,SSSS']'");
        HttpSession session = getHttpSession();

        if (session != null) {
            loggedInUser = (String) session.getAttribute(ServerConstants.USER_LOGGED_IN);
            delegatedBy = (String) session.getAttribute("DELEGATED_BY");

            if (StringUtils.isNotBlank(loggedInUser)) {
                String logMessage = "'" + loggedInUser + "' logged out at " + date.format(currentTime);

                if (delegatedBy != null) {
                    logMessage += " delegated by " + delegatedBy;
                }

                log.info(logMessage);
            }

            session.invalidate();

            if (StringUtils.isNotBlank(loggedInUser) && AUDIT_LOG.isInfoEnabled() &&
                    !LoggerUtils.isEnableV2AuditLogs()) {
                // username in the session is in tenantAware manner
                String tenantAwareUsername = loggedInUser;
                String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();

                String auditInitiator = tenantAwareUsername + UserCoreConstants.TENANT_DOMAIN_COMBINER + tenantDomain;
                String auditData = delegatedBy != null ? "Delegated By : " + delegatedBy : "";

                AUDIT_LOG.info(String.format(SAML2SSOAuthenticatorConstants.AUDIT_MESSAGE,
                        auditInitiator, SAML2SSOAuthenticatorConstants.AUDIT_ACTION_LOGOUT, AUTHENTICATOR_NAME,
                        auditData, SAML2SSOAuthenticatorConstants.AUDIT_RESULT_SUCCESS));
            }
        }
    }

    public boolean isHandle(MessageContext messageContext) {
        return true;
    }

    public boolean isAuthenticated(MessageContext messageContext) {
        HttpServletRequest request = (HttpServletRequest) messageContext
                .getProperty(HTTPConstants.MC_HTTP_SERVLETREQUEST);
        HttpSession httpSession = request.getSession();
        String loginStatus = (String) httpSession.getAttribute(ServerConstants.USER_LOGGED_IN);

        return (loginStatus != null);
    }

    public boolean authenticateWithRememberMe(MessageContext messageContext) {
        return false;
    }

    public int getPriority() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig =
                authenticatorsConfiguration.getAuthenticatorConfig(AUTHENTICATOR_NAME);
        if (authenticatorConfig != null && authenticatorConfig.getPriority() > 0) {
            return authenticatorConfig.getPriority();
        }
        return DEFAULT_PRIORITY_LEVEL;
    }

    public String getAuthenticatorName() {
        return AUTHENTICATOR_NAME;
    }

    public boolean isDisabled() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig =
                authenticatorsConfiguration.getAuthenticatorConfig(AUTHENTICATOR_NAME);
        if (authenticatorConfig != null) {
            return authenticatorConfig.isDisabled();
        }
        return false;
    }

    /**
     * Check whether signature validation is enabled in the authenticators.xml configuration file
     *
     * @return false only if SSOAuthenticator configuration has the parameter
     * <Parameter name="ResponseSignatureValidationEnabled">false</Parameter>, true otherwise
     */
    private boolean isResponseSignatureValidationEnabled() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration
                .getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig = authenticatorsConfiguration
                .getAuthenticatorConfig(AUTHENTICATOR_NAME);
        if (authenticatorConfig != null) {
            String responseSignatureValidation = authenticatorConfig
                    .getParameters()
                    .get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.RESPONSE_SIGNATURE_VALIDATION_ENABLED);
            if (responseSignatureValidation != null
                    && responseSignatureValidation.equalsIgnoreCase("false")) {
                if (log.isDebugEnabled()) {
                    log.debug("Response signature validation is disabled in the configuration");
                }
                return false;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Response signature validation is enabled in the configuration");
        }
        return true;
    }

    /**
     * Check whether the Assertion signature validation is enabled or disabled in the authenticators.xml configuration
     * file
     *
     * @return false only if SAML2SSOAuthenticator configuration has the configuration
     * <Parameter name="AssertionSignatureValidationEnabled">false</Parameter>
     * Otherwise returns true
     */
    private boolean isAssertionSignatureValidationEnabled() {

        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig =
                authenticatorsConfiguration.getAuthenticatorConfig(
                        AUTHENTICATOR_NAME);
        if (authenticatorConfig != null) {
            String assertionSignatureValidation = authenticatorConfig.getParameters().get(
                    SAML2SSOAuthenticatorBEConstants.PropertyConfig.ASSERTION_SIGNATURE_VALIDATION_ENABLED);
            if (assertionSignatureValidation != null && !Boolean.parseBoolean(assertionSignatureValidation)) {
                if (log.isDebugEnabled()) {
                    log.debug("Assertion signature validation is disabled in the configuration");
                }
                return false;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Assertion signature validation is enabled in the configuration");
        }
        return true;
    }

    /**
     * Check whether signature validation is enabled in the authenticators.xml configuration file
     *
     * @return false only if SSOAuthenticator configuration has the parameter
     * <Parameter name="ResponseSignatureValidationEnabled">false</Parameter>, true otherwise
     */
    private boolean isVerifySignWithUserDomain() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration
                .getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig = authenticatorsConfiguration
                .getAuthenticatorConfig(AUTHENTICATOR_NAME);
        if (authenticatorConfig != null) {
            String responseSignatureValidation = authenticatorConfig
                    .getParameters()
                    .get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.VALIDATE_SIGNATURE_WITH_USER_DOMAIN);
            if ("true".equalsIgnoreCase(responseSignatureValidation)) {
                if (log.isDebugEnabled()) {
                    log.debug("Signature validation is done based on user tenant domain");
                }
                return true;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Signature validation is done with super tenant domain");
        }
        return false;
    }

    private int getTimeStampSkewInSeconds() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig = authenticatorsConfiguration
                .getAuthenticatorConfig(AUTHENTICATOR_NAME);

        if (authenticatorConfig != null) {
            String timeStampSkew = authenticatorConfig.getParameters()
                    .get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.TIME_STAMP_SKEW);
            if (timeStampSkew != null) {
                timeStampSkewInSeconds = Integer.parseInt(timeStampSkew);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("TimestampSkew is set to " + timeStampSkewInSeconds + " s.");
        }
        return timeStampSkewInSeconds;
    }

    /**
     * Validate the signature of a SAML2 XMLObject
     *
     * @param xmlObject  SAML2 XMLObject
     * @param domainName domain name of the subject
     * @return true, if signature is valid.
     */
    private boolean validateSignature(XMLObject xmlObject, String domainName) {

        if (xmlObject instanceof Response) {
            Response response = (Response) xmlObject;
            if (!isResponseSignatureValidationEnabled() || validateSignature(response, domainName)) {
                return !isAssertionSignatureValidationEnabled() || validateSignature(getAssertionFromResponse
                        (response), domainName);
            }
        } else if (xmlObject instanceof Assertion) {
            return !isAssertionSignatureValidationEnabled() || validateSignature((Assertion) xmlObject, domainName);
        } else {
            log.error("Only Response and Assertion objects are validated in this authenticator");
        }

        return false;
    }

    /**
     * Validate the signature of a SAML2 Response
     *
     * @param response   SAML2 Response
     * @param domainName domain name of the subject
     * @return true, if signature is valid.
     */
    private boolean validateSignature(Response response, String domainName) {
        boolean isSignatureValid = false;
        if (response == null || response.getSignature() == null) {
            log.error("SAML Response is not signed or response not available. Authentication process will be " +
                    "terminated.");
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Validating SAML Response Signature.");
            }
            isSignatureValid = validateSignature(response.getSignature(), domainName);
        }
        return isSignatureValid;
    }

    /**
     * Validate the signature of a SAML2 Assertion
     *
     * @param assertion  SAML2 Assertion
     * @param domainName domain name of the subject
     * @return true, if signature is valid.
     */
    private boolean validateSignature(Assertion assertion, String domainName) {
        boolean isSignatureValid = false;
        if (assertion == null || assertion.getSignature() == null) {
            log.error("SAML Assertion is not signed or assertion not available. Authentication process will be " +
                    "terminated.");
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Validating SAML Assertion Signature.");
            }
            isSignatureValid = validateSignature(assertion.getSignature(), domainName);
        }
        return isSignatureValid;
    }

    /**
     * Validate the signature of a SAML2 Signature
     *
     * @param signature  SAML2 Signature
     * @param domainName domain name of the subject
     * @return true, if signature is valid.
     */
    private boolean validateSignature(Signature signature, String domainName) {
        boolean isSignatureValid = false;

        try {
            SAMLSignatureProfileValidator signatureProfileValidator = new SAMLSignatureProfileValidator();
            signatureProfileValidator.validate(signature);
        } catch (SignatureException e) {
            String logMsg = "Signature do not confirm to SAML signature profile. Possible XML Signature Wrapping " +
                    "Attack!";
            if (!LoggerUtils.isEnableV2AuditLogs()) {
                AUDIT_LOG.warn(logMsg);
            }
            if (log.isDebugEnabled()) {
                log.debug(logMsg, e);
            }

            return isSignatureValid;
        }

        try {
            if (isVerifySignWithUserDomain()) {
                SignatureValidator.validate(signature, Util.getX509CredentialImplForTenant(domainName));
            } else {
                SignatureValidator.validate(signature, Util.getX509CredentialImplForTenant(MultitenantConstants
                        .SUPER_TENANT_DOMAIN_NAME));
            }

            isSignatureValid = true;
        } catch (SAML2SSOAuthenticatorException e) {
            String errorMsg = "Error when creating an X509CredentialImpl instance";
            log.error(errorMsg, e);
        } catch (SignatureException e) {
            if (log.isDebugEnabled()) {
                log.debug("SAML Signature validation failed from domain : " + domainName, e);
            }
        }
        return isSignatureValid;
    }

    /**
     * Get the Assertion from the SAML2 Response
     *
     * @param response SAML2 Response
     * @return assertion
     */
    private Assertion getAssertionFromResponse(Response response) {
        Assertion assertion = null;
        List<Assertion> assertions = response.getAssertions();
        if (assertions != null && !assertions.isEmpty()) {
            assertion = assertions.get(0);
        } else {
            List<EncryptedAssertion> encryptedAssertions = response.getEncryptedAssertions();
            EncryptedAssertion encryptedAssertion;
            if (encryptedAssertions.size() > 0) {
                encryptedAssertion = encryptedAssertions.get(0);
                try {
                    String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
                    assertion = getDecryptedAssertion(encryptedAssertion, tenantDomain);
                } catch (SAML2SSOUIAuthenticatorException e) {
                    log.error("Error while obtaining the assertion from saml response.", e);
                }
            }
        }
        return assertion;
    }

    /**
     * Validate the AudienceRestriction of SAML2 XMLObject
     *
     * @param xmlObject Unmarshalled SAML2 Response
     * @return validity
     */
    private boolean validateAudienceRestrictionInXML(XMLObject xmlObject) {
        if (xmlObject instanceof Response) {
            return validateAudienceRestrictionInResponse((Response) xmlObject);
        } else if (xmlObject instanceof Assertion) {
            return validateAudienceRestrictionInAssertion((Assertion) xmlObject);
        } else {
            log.error("Only Response and Assertion objects are validated in this authendicator");
            return false;
        }
    }

    /**
     * Validate the AudienceRestriction of SAML2 Response
     *
     * @param response SAML2 Response
     * @return validity
     */
    public boolean validateAudienceRestrictionInResponse(Response response) {
        Assertion assertion = getAssertionFromResponse(response);
        return validateAudienceRestrictionInAssertion(assertion);
    }

    /**
     * Validate the AudienceRestriction of SAML2 Assertion
     *
     * @param assertion SAML2 Assertion
     * @return validity
     */
    public boolean validateAudienceRestrictionInAssertion(Assertion assertion) {
        if (assertion != null) {
            Conditions conditions = assertion.getConditions();
            if (conditions != null) {
                List<AudienceRestriction> audienceRestrictions = conditions.getAudienceRestrictions();
                if (audienceRestrictions != null && !audienceRestrictions.isEmpty()) {
                    for (AudienceRestriction audienceRestriction : audienceRestrictions) {
                        if (audienceRestriction.getAudiences() != null && audienceRestriction.getAudiences().size() > 0) {
                            for (Audience audience : audienceRestriction.getAudiences()) {
                                String spId = org.wso2.carbon.identity.authenticator.saml2.sso.common.Util.getServiceProviderId();
                                if (spId == null) {
                                    org.wso2.carbon.identity.authenticator.saml2.sso.common.Util.initSSOConfigParams();
                                    spId = org.wso2.carbon.identity.authenticator.saml2.sso.common.Util.getServiceProviderId();
                                }
                                if (spId != null) {
                                    if (spId.equals(audience.getAudienceURI())) {
                                        return true;
                                    }
                                } else {
                                    log.warn("No SAML2 service provider ID defined.");
                                }
                            }
                        } else {
                            log.warn("SAML2 Response's AudienceRestriction doesn't contain Audiences");
                        }
                    }
                } else {
                    log.error("SAML2 Response doesn't contain AudienceRestrictions");
                }
            } else {
                log.error("SAML2 Response doesn't contain Conditions");
            }
        }
        return false;
    }

    private HttpSession getHttpSession() {
        MessageContext msgCtx = MessageContext.getCurrentMessageContext();
        HttpSession httpSession = null;
        if (msgCtx != null) {
            HttpServletRequest request =
                    (HttpServletRequest) msgCtx.getProperty(HTTPConstants.MC_HTTP_SERVLETREQUEST);
            httpSession = request.getSession();
        }
        return httpSession;
    }

    /**
     * Provision/Create user on the server(SP) and update roles accordingly
     *
     * @param username
     * @param realm
     * @param xmlObject
     * @throws UserStoreException
     * @throws SAML2SSOAuthenticatorException
     */
    private void provisionUser(String username, UserRealm realm, XMLObject xmlObject) throws UserStoreException, SAML2SSOAuthenticatorException {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig =
                authenticatorsConfiguration.getAuthenticatorConfig(AUTHENTICATOR_NAME);

        try {
            if (authenticatorConfig != null) {
                Map<String, String> configParameters = authenticatorConfig.getParameters();

                boolean isJITProvisioningEnabled = false;
                if (configParameters.containsKey(SAML2SSOAuthenticatorBEConstants.PropertyConfig.JIT_USER_PROVISIONING_ENABLED)) {
                    isJITProvisioningEnabled = Boolean.parseBoolean(configParameters.get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.JIT_USER_PROVISIONING_ENABLED));
                }

                if (isJITProvisioningEnabled) {
                    String userstoreDomain = null;
                    if (configParameters.containsKey(SAML2SSOAuthenticatorBEConstants.PropertyConfig.PROVISIONING_DEFAULT_USERSTORE)) {
                        userstoreDomain = configParameters.get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.PROVISIONING_DEFAULT_USERSTORE);
                    }

                    UserStoreManager userstore = null;

                    // TODO : Get userstore from asserstion
                    // TODO : remove user store domain name from username

                    if (userstoreDomain != null && !userstoreDomain.isEmpty()) {
                        userstore = realm.getUserStoreManager().getSecondaryUserStoreManager(userstoreDomain);
                    }

                    // If default user store is invalid or not specified use primary user store
                    if (userstore == null) {
                        userstore = realm.getUserStoreManager();
                    }

                    String[] newRoles = getRoles(xmlObject);
                    // Load default role if asserstion didnt specify roles
                    if (newRoles == null || newRoles.length == 0) {
                        if (configParameters.containsKey(SAML2SSOAuthenticatorBEConstants.PropertyConfig.PROVISIONING_DEFAULT_ROLE)) {
                            newRoles = new String[]{configParameters.get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.PROVISIONING_DEFAULT_ROLE)};
                        }
                    }
                    if (newRoles == null) {
                        newRoles = new String[]{};
                    }


                    if (log.isDebugEnabled()) {
                        log.debug("User " + username + " contains roles : " + Arrays.toString(newRoles) + " as per response and (default role) config");
                    }

                    // addingRoles = newRoles AND allExistingRoles
                    Collection<String> addingRoles = new ArrayList<String>();
                    Collections.addAll(addingRoles, newRoles);
                    Collection<String> allExistingRoles = Arrays.asList(userstore.getRoleNames());
                    addingRoles.retainAll(allExistingRoles);

                    if (userstore.isExistingUser(username)) {
                        // Update user
                        Collection<String> currentRolesList = Arrays.asList(userstore.getRoleListOfUser(username));
                        // addingRoles = (newRoles AND existingRoles) - currentRolesList)
                        addingRoles.removeAll(currentRolesList);


                        Collection<String> deletingRoles = new ArrayList<String>();
                        deletingRoles.addAll(currentRolesList);
                        // deletingRoles = currentRolesList - newRoles
                        deletingRoles.removeAll(Arrays.asList(newRoles));

                        // Exclude Internal/everyonerole from deleting role since its cannot be deleted
                        deletingRoles.remove(realm.getRealmConfiguration().getEveryOneRoleName());

                        // Check for case whether superadmin login
                        if (userstore.getRealmConfiguration().isPrimary() && username.equals(realm.getRealmConfiguration().getAdminUserName())) {
                            boolean isSuperAdminRoleRequired = false;
                            if (configParameters.containsKey(SAML2SSOAuthenticatorBEConstants.PropertyConfig.IS_SUPER_ADMIN_ROLE_REQUIRED)) {
                                isSuperAdminRoleRequired = Boolean.parseBoolean(configParameters.get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.IS_SUPER_ADMIN_ROLE_REQUIRED));
                            }

                            // Whether superadmin login without superadmin role is permitted
                            if (!isSuperAdminRoleRequired && deletingRoles.contains(realm.getRealmConfiguration().getAdminRoleName())) {
                                // Avoid removing superadmin role from superadmin user.
                                deletingRoles.remove(realm.getRealmConfiguration().getAdminRoleName());
                                log.warn("Proceeding with allowing super admin to be logged in, eventhough response doesn't include superadmin role assiged for the superadmin user.");
                            }
                        }

                        if (log.isDebugEnabled()) {
                            log.debug("Deleting roles : " + Arrays.toString(deletingRoles.toArray(new String[0])) + " and Adding roles : " + Arrays.toString(addingRoles.toArray(new String[0])));
                        }
                        userstore.updateRoleListOfUser(username, deletingRoles.toArray(new String[0]), addingRoles.toArray(new String[0]));
                        if (log.isDebugEnabled()) {
                            log.debug("User: " + username + " is updated via SAML authenticator with roles : " + Arrays.toString(newRoles));
                        }
                    } else {
                        UserCoreUtil.setSkipPasswordPatternValidationThreadLocal(true);
                        userstore.addUser(username, generatePassword(username), addingRoles.toArray(new String[0]), null, null);
                        if (log.isDebugEnabled()) {
                            log.debug("User: " + username + " is provisioned via SAML authenticator with roles : " + Arrays.toString(addingRoles.toArray(new String[0])));
                        }
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("User provisioning diabled");
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Cannot find authenticator config for authenticator : " + AUTHENTICATOR_NAME);
                }
                throw new SAML2SSOAuthenticatorException("Cannot find authenticator config for authenticator : " + AUTHENTICATOR_NAME);
            }
        } finally {
            UserCoreUtil.removeSkipPasswordPatternValidationThreadLocal();
        }
    }

    /**
     * Generates (random) password for user to be provisioned
     *
     * @param username
     * @return
     */
    private String generatePassword(String username) {
        return new BigInteger(130, random).toString(32);
    }

    /**
     * Get roles from the SAML2 XMLObject
     *
     * @param xmlObject SAML2 XMLObject
     * @return String array of roles
     */
    private String[] getRoles(XMLObject xmlObject) {
        String[] arrRoles = {};
        if (xmlObject instanceof Response) {
            return getRolesFromResponse((Response) xmlObject);
        } else if (xmlObject instanceof Assertion) {
            return getRolesFromAssertion((Assertion) xmlObject);
        } else {
            return arrRoles;
        }
    }

    /**
     * Get roles from the SAML2 Response
     *
     * @param response SAML2 Response
     * @return roles array
     */
    private String[] getRolesFromResponse(Response response) {
        List<Assertion> assertions = response.getAssertions();
        Assertion assertion = null;
        if (assertions != null && assertions.size() > 0) {
            assertion = assertions.get(0);
            return getRolesFromAssertion(assertion);
        }
        return null;
    }

    /**
     * Get the username from the SAML2 Assertion
     *
     * @param assertion SAML2 assertion
     * @return username
     */
    private String[] getRolesFromAssertion(Assertion assertion) {
        List<String> roles = new ArrayList<>();
        String roleClaim = getRoleClaim();
        List<AttributeStatement> attributeStatementList = assertion.getAttributeStatements();

        if (attributeStatementList != null) {
            for (AttributeStatement statement : attributeStatementList) {
                List<Attribute> attributesList = statement.getAttributes();
                for (Attribute attribute : attributesList) {
                    String attributeName = attribute.getName();
                    if (attributeName != null && roleClaim.equals(attributeName)) {
                        List<XMLObject> attributeValues = attribute.getAttributeValues();
                        if (attributeValues != null && attributeValues.size() == 1) {
                            String attributeValueString = getAttributeValue(attributeValues.get(0));
                            String multiAttributeSeparator = getAttributeSeperator();
                            String[] attributeValuesArray = attributeValueString.split(multiAttributeSeparator);
                            if (log.isDebugEnabled()) {
                                log.debug("Adding attributes for Assertion: " + assertion + " AttributeName : " +
                                        attributeName + ", AttributeValue : " + Arrays.toString(attributeValuesArray));
                            }
                            roles.addAll(Arrays.asList(attributeValuesArray));
                        } else if (attributeValues != null && attributeValues.size() > 1) {
                            for (XMLObject attributeValue : attributeValues) {
                                String attributeValueString = getAttributeValue(attributeValue);
                                if (log.isDebugEnabled()) {
                                    log.debug("Adding attributes for Assertion: " + assertion + " AttributeName : " +
                                            attributeName + ", AttributeValue : " + attributeValue);
                                }
                                roles.add(attributeValueString);
                            }
                        }
                    }
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Role list found for assertion: " + assertion + ", roles: " + roles);
        }
        return roles.toArray(new String[roles.size()]);
    }

    private String getAttributeValue(XMLObject attributeValue) {
        if (attributeValue == null){
            return null;
        } else if (attributeValue instanceof XSString){
            return getStringAttributeValue((XSString) attributeValue);
        } else if(attributeValue instanceof XSAnyImpl){
            return getAnyAttributeValue((XSAnyImpl) attributeValue);
        } else {
            return attributeValue.toString();
        }
    }

    private String getStringAttributeValue(XSString attributeValue) {
        return attributeValue.getValue();
    }

    private String getAnyAttributeValue(XSAnyImpl attributeValue) {
        return attributeValue.getTextContent();
    }

    /**
     * Role claim attribute value from configuration file or from constants
     *
     * @return
     */
    private String getRoleClaim() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig = authenticatorsConfiguration.getAuthenticatorConfig(AUTHENTICATOR_NAME);

        if (authenticatorConfig != null) {
            Map<String, String> configParameters = authenticatorConfig.getParameters();
            if (configParameters.containsKey(SAML2SSOAuthenticatorBEConstants.PropertyConfig.ROLE_CLAIM_ATTRIBUTE)) {
                return configParameters.get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.ROLE_CLAIM_ATTRIBUTE);
            }
        }

        return SAML2SSOAuthenticatorBEConstants.ROLE_ATTRIBUTE_NAME;
    }

    /**
     * Get attribute separator from configuration or from the constants
     *
     * @return
     */
    private String getAttributeSeperator() {
        AuthenticatorsConfiguration authenticatorsConfiguration = AuthenticatorsConfiguration.getInstance();
        AuthenticatorsConfiguration.AuthenticatorConfig authenticatorConfig = authenticatorsConfiguration.getAuthenticatorConfig(AUTHENTICATOR_NAME);

        if (authenticatorConfig != null) {
            Map<String, String> configParameters = authenticatorConfig.getParameters();
            if (configParameters.containsKey(SAML2SSOAuthenticatorBEConstants.PropertyConfig.ATTRIBUTE_VALUE_SEPARATOR)) {
                return configParameters.get(SAML2SSOAuthenticatorBEConstants.PropertyConfig.ATTRIBUTE_VALUE_SEPARATOR);
            }
        }

        return SAML2SSOAuthenticatorBEConstants.ATTRIBUTE_VALUE_SEPERATER;
    }

    /**
     * Validates the 'Not Before' and 'Not On Or After' conditions of the SAML Assertion
     *
     * @param xmlObject SAML Assertion element
     * @throws SAML2SSOAuthenticatorException
     */
    private void validateAssertionValidityPeriod(XMLObject xmlObject) throws SAML2SSOAuthenticatorException {

        Assertion assertion;
        if (xmlObject instanceof Response) {
            assertion = getAssertionFromResponse((Response) xmlObject);
        } else if (xmlObject instanceof Assertion) {
            assertion = (Assertion) xmlObject;
        } else {
            throw new SAML2SSOAuthenticatorException(
                    "Only Response and Assertion objects are validated in this authenticator");
        }

        if (assertion == null) {
            throw new SAML2SSOAuthenticatorException("Cannot find a SAML Assertion");
        }

        if (assertion.getConditions() != null) {
            DateTime validFrom = assertion.getConditions().getNotBefore();
            DateTime validTill = assertion.getConditions().getNotOnOrAfter();
            int timeStampSkewInSeconds = getTimeStampSkewInSeconds();

            if (validFrom != null && validFrom.minusSeconds(timeStampSkewInSeconds).isAfterNow()) {
                throw new SAML2SSOAuthenticatorException("Failed to meet SAML Assertion Condition 'Not Before'");
            }

            if (validTill != null && validTill.plusSeconds(timeStampSkewInSeconds).isBeforeNow()) {
                throw new SAML2SSOAuthenticatorException(
                        "Failed to meet SAML Assertion Condition 'Not On Or After'");
            }

            if (validFrom != null && validTill != null && validFrom.isAfter(validTill)) {
                throw new SAML2SSOAuthenticatorException(
                        "SAML Assertion Condition 'Not Before' must be less than the " +
                                "value of 'Not On Or After'");
            }
        }
    }
}