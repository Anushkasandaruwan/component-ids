/*******************************************************************************
 * Copyright (c) 2015-2017, WSO2.Telco Inc. (http://www.wso2telco.com)
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

package com.wso2telco.gsma.authenticators.saa;

import com.google.gson.Gson;
import com.wso2telco.core.config.ReadMobileConnectConfig;
import com.wso2telco.gsma.authenticators.AuthenticatorException;
import com.wso2telco.gsma.authenticators.Constants;
import com.wso2telco.gsma.authenticators.DBUtils;
import com.wso2telco.gsma.authenticators.Utility;
import com.wso2telco.gsma.authenticators.exception.SaaException;
import com.wso2telco.gsma.authenticators.model.UserState;
import com.wso2telco.gsma.authenticators.model.UserStatus;
import com.wso2telco.core.sp.config.utils.service.SpConfigService;
import com.wso2telco.core.sp.config.utils.service.impl.SpConfigServiceImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticatorFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.LocalApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.config.ConfigurationFacade;
import org.wso2.carbon.identity.application.authentication.framework.config.model.ApplicationConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.exception.LogoutFailedException;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SmartPhoneAppAuthenticator extends AbstractApplicationAuthenticator
        implements LocalApplicationAuthenticator {

    private static Log log = LogFactory.getLog(SmartPhoneAppAuthenticator.class);

    private static final String IS_ERROR_OCCURRED = "isErrorOccurred";
    private static final String PIN_CLAIM = "http://wso2.org/claims/pin";
    private static final String MSISDN = "msisdn";
    private static final String CLIENT_ID = "relyingParty";
    private static final String ACR = "acr_values";
    private SpConfigService spConfigService = new SpConfigServiceImpl();

    @Override
    public boolean canHandle(HttpServletRequest request) {
        return true;
    }

    @Override
    public AuthenticatorFlowStatus process(HttpServletRequest request,
                                           HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException, LogoutFailedException {

        if (context.isLogoutRequest()) {
            return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
        } else {
            super.process(request, response, context);

            boolean isErrorOccurred = (boolean) context.getProperty(IS_ERROR_OCCURRED);

            if (isErrorOccurred) {
                return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
            } else {
                return AuthenticatorFlowStatus.INCOMPLETE;
            }
        }
    }

    @Override
    protected void initiateAuthenticationRequest(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

        log.info("Initiating smart phone authentication");

        boolean isErrorOccurred = false;

        String retryParam = "";
        String queryParams = FrameworkUtils.getQueryStringWithFrameworkContextId(context.getQueryParams(),
                context.getCallerSessionKey(), context.getContextIdentifier());

        Map<String, String> paramMap = createQueryParamMap(queryParams);
        ApplicationConfig applicationConfig = context.getSequenceConfig().getApplicationConfig();

        String msisdn = (String) context.getProperty(MSISDN);
        String clientId = paramMap.get(CLIENT_ID);
        String applicationName = applicationConfig.getApplicationName();

        String url = ReadMobileConnectConfig.readSaaConfig(Constants.SAA_ENPOINT) + "/services/serverAPI/api/v1/clients/"
                + msisdn + "/authenticate";

        handleRetry(request, context, msisdn);
        try {
            if (!context.isRetrying()) {
                DBUtils.insertUserResponse(context.getContextIdentifier(), String.valueOf(SmartPhoneAppAuthenticator.UserResponse.PENDING));
            } else {
                retryParam = "&authFailure=true&authFailureMsg=login.fail.message";
            }
            fallbackIfMsisdnNotRegistered(msisdn);

            SaaRequest saaRequest = createSaaRequest(paramMap, clientId, applicationName);

            StringEntity postData = new StringEntity(new Gson().toJson(saaRequest));

            postDataToSaaServer(url, postData);

        } catch (IOException e) {
            log.info("Error occurred while posting data to SAA server", e);
            isErrorOccurred = true;
        } catch (SaaException e) {
            log.info("SAA server returned invalid http status", e);
            isErrorOccurred = true;
        } catch (AuthenticatorException e) {
            log.info("Error occurred while retrieving authentication details form database", e);
            isErrorOccurred = true;
        } catch (Exception e) {
            log.info("Error occurred", e);
            isErrorOccurred = true;
        } finally {
            handleRedirect(response, context, isErrorOccurred, retryParam);
        }
    }

    private void handleRedirect(HttpServletResponse response, AuthenticationContext context, boolean isErrorOccurred, String retryParam) throws AuthenticationFailedException {

        String loginPage = ConfigurationFacade.getInstance().getAuthenticationEndpointURL();
        context.setProperty(IS_ERROR_OCCURRED, isErrorOccurred);

        if (!isErrorOccurred) {
            String redirectUrl = response.encodeRedirectURL(loginPage + ("?" + context.getQueryParams())) + "&scope=" + (String) context.getProperty("scope")
                    + "&redirect_uri=" + context.getProperty("redirectURI")
                    + "&authenticators=" + getName() + ":" + "LOCAL" + retryParam + Utility.getMultiScopeQueryParam(context);

            log.info("Sent request to the SAA server successfully. Redirecting to [ " + redirectUrl + " ] ");

            try {
                response.sendRedirect(redirectUrl);
            } catch (IOException e) {
                log.info("Error occurred while redirecting to waiting page. Passing control to the next authenticator");
            }
        } else {
            log.info("Passing control to the next authenticator");
        }
    }

    private void fallbackIfMsisdnNotRegistered(String msisdn) throws IOException, SaaException {
        HttpClient httpClient = new DefaultHttpClient();

        String url = ReadMobileConnectConfig.readSaaConfig(Constants.SAA_ENPOINT) + "/services/serverAPI/api/v1/clients/" +
                msisdn + "/is_registered";

        HttpGet httpGet = new HttpGet(url);

        HttpResponse httpResponse = httpClient.execute(httpGet);

        IsRegisteredResponse isRegisteredResponse = new Gson()
                .fromJson(EntityUtils.toString(httpResponse.getEntity()), IsRegisteredResponse.class);

        if (!isRegisteredResponse.isRegistered()) {
            throw new SaaException("msisdn [ " + msisdn + " ] is not registered in SAA server");
        }
    }

    private void postDataToSaaServer(String url, StringEntity postData) throws SaaException, IOException {
        HttpClient httpClient = new DefaultHttpClient();
        HttpPost httpPost = new HttpPost(url);

        postData.setContentType("application/json");
        httpPost.setEntity(postData);

        HttpResponse httpResponse = httpClient.execute(httpPost);

        if (httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            log.error("SAA server replied with invalid HTTP status [ " + httpResponse.getStatusLine().getStatusCode() + "] ");

            throw new SaaException("Error occurred while posting data to SAA server");
        }
    }

    private void handleRetry(HttpServletRequest request, AuthenticationContext context, String msisdn) {
        if (context.isRetrying()) {
            UserStatus uStatus = new UserStatus();

            Utility.setValueFromContext(request, context, uStatus);
            uStatus.setStatus(UserState.MSISDN_AUTH_PROCESSING_FAIL.name());
            if (msisdn != null && !msisdn.isEmpty()) {
                uStatus.setComment("Initializing Failed");
                uStatus.setIsNewUser(1);
                uStatus.setIsMsisdnHeader(1);
            } else {
                uStatus.setIsMsisdnHeader(0);

            }
            Utility.saveUSSD(uStatus);
        }
    }

    private Map<String, String> createQueryParamMap(String params) {
        String[] queryParams = params.split("&");
        Map<String, String> paramMap = new HashMap<>();

        for (String queryParam : queryParams) {
            String[] param = queryParam.split("=");
            String key = param[0];
            String value = null;
            if (param.length > 1) {
                value = param[1];
            }
            paramMap.put(key, value);
        }
        return paramMap;
    }

    private SaaRequest createSaaRequest(Map<String, String> paramMap, String clientId, String applicationName) throws Exception {
        SaaRequest saaRequest = new SaaRequest();
        saaRequest.setApplicationName(applicationName);
        saaRequest.setMessage(spConfigService.getSaaMessage(clientId));
        saaRequest.setAcr(paramMap.get(ACR));
        saaRequest.setSpImgUrl(spConfigService.getSaaImageUrl(clientId));
        saaRequest.setRef("21231231231231");// TODO: 11/25/16 add correct value for the ref parameter
        return saaRequest;
    }

    @Override
    protected void processAuthenticationResponse(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

    }

    @Override
    protected boolean retryAuthenticationEnabled() {
        return false;
    }

    @Override
    public String getContextIdentifier(HttpServletRequest request) {
        return request.getParameter("sessionDataKey");
    }

    @Override
    public String getFriendlyName() {
        return Constants.SAA_AUTHENTICATOR_FRIENDLY_NAME;
    }

    @Override
    public String getName() {
        return Constants.SAA_AUTHENTICATOR_NAME;
    }

    private enum UserResponse {

        PENDING,
        APPROVED,
        REJECTED
    }
}