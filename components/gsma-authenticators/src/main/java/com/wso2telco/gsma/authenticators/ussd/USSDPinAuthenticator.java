/*******************************************************************************
 * Copyright (c) 2015-2016, WSO2.Telco Inc. (http://www.wso2telco.com) 
 *
 * All Rights Reserved. WSO2.Telco Inc. licences this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.wso2telco.gsma.authenticators.ussd;

import com.wso2telco.core.config.model.PinConfig;
import com.wso2telco.core.config.service.ConfigurationService;
import com.wso2telco.core.config.service.ConfigurationServiceImpl;
import com.wso2telco.core.config.util.PinConfigUtil;
import com.wso2telco.gsma.authenticators.AuthenticatorException;
import com.wso2telco.gsma.authenticators.Constants;
import com.wso2telco.gsma.authenticators.internal.CustomAuthenticatorServiceComponent;
import com.wso2telco.gsma.authenticators.ussd.command.PinLoginUssdCommand;
import com.wso2telco.gsma.authenticators.ussd.command.PinRegistrationUssdCommand;
import com.wso2telco.gsma.authenticators.ussd.command.UssdCommand;
import com.wso2telco.gsma.authenticators.util.AuthenticationContextHelper;
import com.wso2telco.gsma.authenticators.util.UserProfileManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticatorFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.LocalApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.config.ConfigurationFacade;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.exception.LogoutFailedException;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.cache.BaseCache;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.dao.OAuthAppDAO;
import org.wso2.carbon.identity.oauth.dao.OAuthAppDO;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.user.registration.stub.UserRegistrationAdminServiceIdentityException;
import org.wso2.carbon.um.ws.api.stub.RemoteUserStoreManagerServiceUserStoreExceptionException;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.core.UserStoreManager;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.rmi.RemoteException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;


// TODO: Auto-generated Javadoc

/**
 * The Class USSDPinAuthenticator.
 */
public class USSDPinAuthenticator extends AbstractApplicationAuthenticator
        implements LocalApplicationAuthenticator {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 7785133722588291678L;

    /**
     * The log.
     */
    private static Log log = LogFactory.getLog(USSDPinAuthenticator.class);

    /**
     * The Constant PIN_CLAIM.
     */
    private static final String PIN_CLAIM = "http://wso2.org/claims/pin";

    /**
     * The Configuration service
     */
    private static ConfigurationService configurationService = new ConfigurationServiceImpl();

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.ApplicationAuthenticator#canHandle(javax.servlet.http.HttpServletRequest)
     */
    @Override
    public boolean canHandle(HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("USSD Authenticator canHandle invoked");
        }
        return true;
    }

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator#process(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext)
     */
    @Override
    public AuthenticatorFlowStatus process(HttpServletRequest request,
                                           HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException, LogoutFailedException {

        if (context.isLogoutRequest()) {
            return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
        } else {
            return super.process(request, response, context);
        }
    }

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator#initiateAuthenticationRequest(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext)
     */
    @Override
    protected void initiateAuthenticationRequest(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

        String retryParam = "";
        boolean isRegistering = (boolean) context.getProperty(Constants.IS_REGISTERING);
        boolean isPinReset = (boolean) context.getProperty(Constants.IS_PIN_RESET);
        String msisdn = (String) context.getProperty(Constants.MSISDN);
        String serviceProviderName = context.getSequenceConfig().getApplicationConfig().getApplicationName();

        log.info("Initiating authentication request [ msisdn : " + msisdn + " , service provider : " + serviceProviderName + " ] ");

        try {

            String loginPage = getAuthEndpointUrl(context);

            String queryParams = FrameworkUtils.getQueryStringWithFrameworkContextId(context.getQueryParams(),
                    context.getCallerSessionKey(), context.getContextIdentifier());

            log.info("Service Provider Name = " + serviceProviderName);
            if (serviceProviderName.equals("wso2_sp_dashboard")) {
                serviceProviderName = configurationService.getDataHolder().getMobileConnectConfig().getUssdConfig().getDashBoard();
            }
            String operator = (String) context.getProperty("operator");

            savePinConfigToContext(context, isRegistering, msisdn, isPinReset);

            if (!isPinReset) {
                saveLoa3PropertiesToContext(request, context);
                sendUssd(context, isRegistering, msisdn, serviceProviderName, operator);
            }

            response.sendRedirect(response.encodeRedirectURL(loginPage + ("?" + queryParams))
                    + "&redirect_uri=" + context.getProperty("redirectURI")
                    + "&authenticators=" + getName() + ":" + "LOCAL" + retryParam + "&sessionDataKey=" + context.getContextIdentifier());

        } catch (IOException e) {
            log.error("Error occurred while redirecting the request", e);
            throw new AuthenticationFailedException(e.getMessage(), e);
        } catch (SQLException | AuthenticatorException e) {
            log.error("Error occurred while inserting registration status", e);
            throw new AuthenticationFailedException(e.getMessage(), e);
        } catch (RemoteUserStoreManagerServiceUserStoreExceptionException e) {
            log.error("Error occurred while getting user pin", e);
            throw new AuthenticationFailedException(e.getMessage(), e);
        }
    }

    private void savePinConfigToContext(AuthenticationContext context, boolean isRegistering, String msisdn,
                                        boolean isPinReset)
            throws RemoteUserStoreManagerServiceUserStoreExceptionException, RemoteException {


        PinConfig pinConfig;
        if (isRegistering) {

            pinConfig = new PinConfig();
            pinConfig.setInvalidFormatAttempts(0);
            pinConfig.setCurrentStep(PinConfig.CurrentStep.REGISTRATION);
        } else if (isPinReset) {
            pinConfig = PinConfigUtil.getPinConfig(context);

            String challengeQuestionAndAnswer1 = UserProfileManager.getChallengeQuestionAndAnswer1(msisdn);
            String challengeQuestionAndAnswer2 = UserProfileManager.getChallengeQuestionAndAnswer2(msisdn);

            pinConfig.setChallengeQuestion1(challengeQuestionAndAnswer1.split("!")[0]);
            pinConfig.setChallengeQuestion2(challengeQuestionAndAnswer2.split("!")[0]);
            pinConfig.setChallengeAnswer1(challengeQuestionAndAnswer1.split("!")[1]);
            pinConfig.setChallengeAnswer2(challengeQuestionAndAnswer2.split("!")[1]);

        } else {
            pinConfig = new PinConfig();
            String registeredPin = UserProfileManager.getCurrentPin(msisdn);
            pinConfig.setRegisteredPin(registeredPin);
            pinConfig.setCurrentStep(PinConfig.CurrentStep.LOGIN);
        }
        pinConfig.setMsisdn(msisdn);
        pinConfig.setPinMismatchAttempts(0);
        pinConfig.setSessionId(context.getContextIdentifier());
        pinConfig.setTotalAttempts(0);

        PinConfigUtil.savePinConfigToContext(pinConfig, context);
    }

    private void sendUssd(AuthenticationContext context, boolean isRegistering, String msisdn, String serviceProviderName, String operator) throws SQLException, AuthenticatorException, IOException {
        UssdCommand ussdCommand;
        boolean isProfileUpgrade = (boolean) context.getProperty(Constants.IS_PROFILE_UPGRADE);
        if (isRegistering || isProfileUpgrade) {
            ussdCommand = new PinRegistrationUssdCommand();
        } else {
            ussdCommand = new PinLoginUssdCommand();
        }
        ussdCommand.execute(msisdn, context.getContextIdentifier(), serviceProviderName, operator);
    }

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator#processAuthenticationResponse(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext)
     */
    @Override
    protected void processAuthenticationResponse(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

        String msisdn = (String) context.getProperty(Constants.MSISDN);
        PinConfig pinConfig = PinConfigUtil.getPinConfig(context);

        boolean isRegistering = (boolean) context.getProperty(Constants.IS_REGISTERING);
        boolean isProfileUpgrade = (boolean) context.getProperty(Constants.IS_PROFILE_UPGRADE);
        boolean isPinReset = isPinReset(pinConfig);
        boolean isPinResetConfirmation = isPinResetConfirmation(pinConfig);

        log.info("Processing authentication request [ msisdn : " + msisdn + " ] ");
        try {
            if (isRegistering) {
                handleUserRegistration(context);
            } else {
                if (isProfileUpgrade) {
                    handleProfileUpgrade(context);
                } else if (isPinReset) {
                    retryAuthenticatorForPinReset(context);
                } else if (isPinResetConfirmation) {
                    handlePinResetConfirmation(msisdn, pinConfig);
                } else {
                    handleUserLogin(context);
                }
            }
            AuthenticationContextHelper.setSubject(context, msisdn);

            context.setRememberMe(false);
            log.info("UssdPinAuthenticator successfully completed");

        } catch (UserRegistrationAdminServiceIdentityException | RemoteException e) {
            log.error("Error occurred while creating user profile", e);
//            throw new AuthenticationFailedException(e.getMessage(), e);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            log.error("Error occurred while accessing admin services", e);
//            throw new AuthenticationFailedException(e.getMessage(), e);
        } catch (RemoteUserStoreManagerServiceUserStoreExceptionException e) {
            log.error("Error occurred while updating user profile", e);
//            throw new AuthenticationFailedException(e.getMessage(), e);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            log.error("Error occurred while hashing the pin", e);
//            throw new AuthenticationFailedException(e.getMessage(), e);
        }
    }

    private void handlePinResetConfirmation(String msisdn, PinConfig pinConfig) throws RemoteException, NoSuchAlgorithmException,
            RemoteUserStoreManagerServiceUserStoreExceptionException, UnsupportedEncodingException {

        UserProfileManager.setCurrentPin(msisdn, pinConfig.getConfirmedPin());
    }

    private boolean isPinResetConfirmation(PinConfig pinConfig) {
        return pinConfig.getCurrentStep() == PinConfig.CurrentStep.PIN_RESET_CONFIRMATION;
    }

    private void retryAuthenticatorForPinReset(AuthenticationContext context) throws AuthenticationFailedException, RemoteUserStoreManagerServiceUserStoreExceptionException, RemoteException {
        log.info("Retrying authenticator for pin reset flow");
        String msisdn = (String) context.getProperty(Constants.MSISDN);
        String challengeQuestionAndAnswer1 = UserProfileManager.getChallengeQuestionAndAnswer1(msisdn);
        String challengeQuestionAndAnswer2 = UserProfileManager.getChallengeQuestionAndAnswer2(msisdn);

        String challengeQuestion1 = challengeQuestionAndAnswer1.split("!")[0];
        String challengeQuestion2 = challengeQuestionAndAnswer2.split("!")[0];
        String challengeAnswer1 = challengeQuestionAndAnswer1.split("!")[1];
        String challengeAnswer2 = challengeQuestionAndAnswer2.split("!")[1];

        context.setProperty(Constants.IS_PIN_RESET, true);
        context.setProperty(Constants.CHALLENGE_QUESTION_1, challengeQuestion1);
        context.setProperty(Constants.CHALLENGE_QUESTION_2, challengeQuestion2);

        PinConfig pinConfig = PinConfigUtil.getPinConfig(context);
        pinConfig.setChallengeQuestion1(challengeQuestion1);
        pinConfig.setChallengeQuestion2(challengeQuestion2);
        pinConfig.setChallengeAnswer1(challengeAnswer1);
        pinConfig.setChallengeAnswer1(challengeAnswer2);

        throw new AuthenticationFailedException("User entered an incorrect pin for login. Moving to pin reset");
    }

    private boolean isPinReset(PinConfig pinConfig) {
        return pinConfig.getCurrentStep() == PinConfig.CurrentStep.PIN_RESET;
    }

    private void handleProfileUpgrade(AuthenticationContext context) throws RemoteUserStoreManagerServiceUserStoreExceptionException, RemoteException, UnsupportedEncodingException, NoSuchAlgorithmException {
        String challengeAnswer1 = (String) context.getProperty(Constants.CHALLENGE_ANSWER_1);
        String challengeAnswer2 = (String) context.getProperty(Constants.CHALLENGE_ANSWER_2);
        String challengeQuestion1 = (String) context.getProperty(Constants.CHALLENGE_QUESTION_1);
        String challengeQuestion2 = (String) context.getProperty(Constants.CHALLENGE_QUESTION_2);
        String msisdn = (String) context.getProperty(Constants.MSISDN);
        String operator = (String) context.getProperty(Constants.OPERATOR);
        PinConfig pinConfig = (PinConfig) context.getProperty(com.wso2telco.core.config.util.Constants.PIN_CONFIG_OBJECT);

        log.info("Updating user profile from LOA2 to LOA3 flow [ msisdn : " + msisdn + " , challenge question 1 : " +
                challengeQuestion1 + " , challenge answer 1 : " + challengeAnswer1 + " , challenge question 2 : " +
                challengeQuestion2 + " , challenge answer 2 : " + challengeAnswer2 + " ] ");

        challengeAnswer1 = challengeQuestion1 + Constants.USER_CHALLENGE_SEPARATOR + challengeAnswer1;
        challengeAnswer2 = challengeQuestion2 + Constants.USER_CHALLENGE_SEPARATOR + challengeAnswer2;

        UserProfileManager.updateUserProfileForLOA3(challengeAnswer1, challengeAnswer2, pinConfig.getConfirmedPin(), msisdn);
    }

    private void handleUserRegistration(AuthenticationContext context) throws UserRegistrationAdminServiceIdentityException, RemoteException, AuthenticationFailedException {
        String challengeAnswer1 = (String) context.getProperty(Constants.CHALLENGE_ANSWER_1);
        String challengeAnswer2 = (String) context.getProperty(Constants.CHALLENGE_ANSWER_2);
        String challengeQuestion1 = (String) context.getProperty(Constants.CHALLENGE_QUESTION_1);
        String challengeQuestion2 = (String) context.getProperty(Constants.CHALLENGE_QUESTION_2);
        String msisdn = (String) context.getProperty(Constants.MSISDN);
        String operator = (String) context.getProperty(Constants.OPERATOR);
        PinConfig pinConfig = PinConfigUtil.getPinConfig(context);

        if (pinConfig.isPinsMatched()) {
            log.info("Creating user profile for LOA3 flow [ msisdn : " + msisdn + " , challenge question 1 : " +
                    challengeQuestion1 + " , challenge answer 1 : " + challengeAnswer1 + " , challenge question 2 : " +
                    challengeQuestion2 + " , challenge answer 2 : " + challengeAnswer2 + " ] ");

            challengeAnswer1 = challengeQuestion1 + Constants.USER_CHALLENGE_SEPARATOR + challengeAnswer1;
            challengeAnswer2 = challengeQuestion2 + Constants.USER_CHALLENGE_SEPARATOR + challengeAnswer2;

            UserProfileManager.createUserProfileLoa3(msisdn, operator, challengeAnswer1, challengeAnswer2,
                    pinConfig.getRegisteredPin());
        } else {
            throw new AuthenticationFailedException("Authentication failed for due to mismatch in entered and confirmed pin");
        }
    }

    private void handleUserLogin(AuthenticationContext context) throws org.wso2.carbon.user.api.UserStoreException, AuthenticationFailedException, RemoteUserStoreManagerServiceUserStoreExceptionException, RemoteException {

        String msisdn = (String) context.getProperty(Constants.MSISDN);
        PinConfig pinConfig = PinConfigUtil.getPinConfig(context);
        String registeredPin = UserProfileManager.getCurrentPin(msisdn);
        String confirmedPin = pinConfig.getConfirmedPin();


        log.info("Handling user login [ msisdn : " + msisdn + "]");

        int tenantId = -1234;
        UserRealm userRealm = CustomAuthenticatorServiceComponent.getRealmService()
                .getTenantUserRealm(tenantId);

        if (userRealm != null) {
            UserStoreManager userStoreManager = (UserStoreManager) userRealm.getUserStoreManager();
            String profilePin = userStoreManager.getUserClaimValue(msisdn, PIN_CLAIM, null);

            validatePin(pinConfig);

        } else {
            throw new AuthenticationFailedException("Cannot find the user realm for the given tenant: " + tenantId);
        }
    }

    private void validatePin(PinConfig pinConfig) throws AuthenticationFailedException {

        if (pinConfig.isPinsMatched()) {
            log.info("User entered a correct pin. Authentication Success");
        } else {
            log.error("Authentication failed. User entered an incorrect pin");
            throw new AuthenticationFailedException("Authentication failed due to incorrect pin");
        }
    }

    private void saveLoa3PropertiesToContext(HttpServletRequest request, AuthenticationContext context) {

        context.setProperty(Constants.CHALLENGE_QUESTION_1, request.getParameter(Constants.CHALLENGE_QUESTION_1));
        context.setProperty(Constants.CHALLENGE_QUESTION_2, request.getParameter(Constants.CHALLENGE_QUESTION_2));
        context.setProperty(Constants.CHALLENGE_ANSWER_1, request.getParameter(Constants.CHALLENGE_ANSWER_1));
        context.setProperty(Constants.CHALLENGE_ANSWER_2, request.getParameter(Constants.CHALLENGE_ANSWER_2));
        context.setProperty(Constants.NO_OF_ATTEMPTS, 0);
    }

    private String getAuthEndpointUrl(AuthenticationContext context) {
        boolean isRegistering = (boolean) context.getProperty(Constants.IS_REGISTERING);
        boolean isPinReset = (boolean) context.getProperty(Constants.IS_PIN_RESET);
        String loginPage;

        if (isRegistering) {
            context.setProperty(Constants.IS_REGISTERING, true);
            loginPage = configurationService.getDataHolder().getMobileConnectConfig().getAuthEndpointUrl()
                    + Constants.PIN_REGISTRATION_WAITING_JSP;
        } else if (isPinReset) {
            loginPage = configurationService.getDataHolder().getMobileConnectConfig().getAuthEndpointUrl()
                    + Constants.PIN_RESET_JSP;
        } else {
            loginPage = ConfigurationFacade.getInstance().getAuthenticationEndpointURL();
        }
        return loginPage;
    }

    /**
     * Gets the hashed pin.
     *
     * @param pinvalue the pinvalue
     * @return the hashed pin
     */
    private String getHashedPin(String pinvalue) {
        String hashString = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(pinvalue.getBytes("UTF-8"));

            StringBuilder hexString = new StringBuilder();

            for (int i = 0; i < hash.length; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            hashString = hexString.toString();

        } catch (UnsupportedEncodingException ex) {
            log.error("Error getHashValue" + ex);
        } catch (NoSuchAlgorithmException ex) {
            log.error("Error getHashValue" + ex);
        }

        return hashString;

    }


    /**
     * Gets the app information.
     *
     * @param clientID the client id
     * @return the app information
     * @throws IdentityOAuth2Exception     the identity o auth2 exception
     * @throws InvalidOAuthClientException the invalid o auth client exception
     */
    private static OAuthAppDO getAppInformation(String clientID)
            throws IdentityOAuth2Exception, InvalidOAuthClientException {
        BaseCache<String, OAuthAppDO> appInfoCache = new BaseCache<String, OAuthAppDO>(
                "AppInfoCache"); //$NON-NLS-1$
        if (null != appInfoCache) {
            if (log.isDebugEnabled()) {
                log.debug("Successfully created AppInfoCache under " //$NON-NLS-1$
                        + OAuthConstants.OAUTH_CACHE_MANAGER);
            }
        }

        OAuthAppDO oAuthAppDO = appInfoCache.getValueFromCache(clientID);
        if (oAuthAppDO != null) {
            return oAuthAppDO;
        } else {
            oAuthAppDO = new OAuthAppDAO().getAppInformation(clientID);
            appInfoCache.addToCache(clientID, oAuthAppDO);
            return oAuthAppDO;
        }
    }

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator#retryAuthenticationEnabled()
     */
    @Override
    protected boolean retryAuthenticationEnabled() {
        return true;
    }

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.ApplicationAuthenticator#getContextIdentifier(javax.servlet.http.HttpServletRequest)
     */
    @Override
    public String getContextIdentifier(HttpServletRequest request) {
        return request.getParameter("sessionDataKey");
    }

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.ApplicationAuthenticator#getFriendlyName()
     */
    @Override
    public String getFriendlyName() {
        return Constants.USSDPIN_AUTHENTICATOR_FRIENDLY_NAME;
    }

    /* (non-Javadoc)
     * @see org.wso2.carbon.identity.application.authentication.framework.ApplicationAuthenticator#getName()
     */
    @Override
    public String getName() {
        return Constants.USSDPIN_AUTHENTICATOR_NAME;
    }

    /**
     * The Enum UserResponse.
     */
    private enum UserResponse {

        /**
         * The pending.
         */
        PENDING,

        /**
         * The approved.
         */
        APPROVED,

        /**
         * The rejected.
         */
        REJECTED
    }
}
