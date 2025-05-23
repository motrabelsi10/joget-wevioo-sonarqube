package org.joget.commons.util;

import java.net.URI;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.owasp.csrfguard.CsrfGuard;
import org.owasp.csrfguard.session.LogicalSession;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;

/**
 * Utility methods used by security feature
 * 
 */
@Service("securityUtil")
public class SecurityUtil implements ApplicationContextAware {

    public final static String ENVELOPE = "%%%%";
    private static ApplicationContext appContext;

    /**
     * Utility method to retrieve the ApplicationContext of the system
     * @return 
     */
    public static ApplicationContext getApplicationContext() {
        return appContext;
    }

    /**
     * Method used by system to set an ApplicationContext
     * @param context
     * @throws BeansException 
     */
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        appContext = context;
    }

    /**
     * Gets the data encryption implementation
     * @return 
     */
    public static DataEncryption getDataEncryption() {
        try {
            DataEncryption de = (DataEncryption) getApplicationContext().getBean("dataEncryption");
            return de;
        } catch(Exception e) {
            return null;
        }
    }
    
    /**
     * Gets the nonce generator implementation
     * @return 
     */
    public static NonceGenerator getNonceGenerator() {
        try {
            NonceGenerator ng = (NonceGenerator) getApplicationContext().getBean("nonceGenerator");
            return ng;
        } catch(Exception e) {
            return null;
        }
    }

    /**
     * Encrypt raw content if data encryption implementation is exist
     * @param rawContent
     * @return 
     */
    public static String encrypt(String rawContent) {
        DataEncryption de = getDataEncryption();
        if (rawContent != null && de != null) {
            try {
                return ENVELOPE + de.encrypt(rawContent) + ENVELOPE;
            } catch (Exception e) {
                LogUtil.warn(SecurityUtil.class.getName(), "Cannot encrypt content: " + e.toString());
            }
        }
        return rawContent;
    }

    /**
     * Decrypt protected content if data encryption implementation is exist
     * @param protectedContent
     * @return 
     */
    public static String decrypt(String protectedContent) {
        DataEncryption de = getDataEncryption();
        if (protectedContent != null && protectedContent.startsWith(ENVELOPE) && protectedContent.endsWith(ENVELOPE) && de != null) {
            try {
                String tempProtectedContent = cleanPrefixPostfix(protectedContent);
                return de.decrypt(tempProtectedContent);
            } catch (Exception e) {
                LogUtil.warn(SecurityUtil.class.getName(), "Cannot decrypt content: " + e.toString());
            }
        }
        return protectedContent;
    }

    /**
     * Computes the hash of a raw content if data encryption implementation is exist
     * @param rawContent
     * @param randomSalt
     * @return 
     */
    public static String computeHash(String rawContent, String randomSalt) {
        DataEncryption de = getDataEncryption();
        if (rawContent != null && !rawContent.isEmpty()) {
            if (de != null) {
                return ENVELOPE + de.computeHash(rawContent, randomSalt) + ENVELOPE;
            } else {
                return StringUtil.md5Base16(rawContent);
            }
        }
        return rawContent;
    }

    /**
     * Verify the hash is belong to the raw content if data encryption 
     * implementation is exist
     * @param hash
     * @param randomSalt
     * @param rawContent
     * @return 
     */
    public static Boolean verifyHash(String hash, String randomSalt, String rawContent) {
        if (hash != null && !hash.isEmpty() && rawContent != null && !rawContent.isEmpty()) {
            DataEncryption de = getDataEncryption();
            if (hash.startsWith(ENVELOPE) && hash.endsWith(ENVELOPE) && de != null) {
                hash = cleanPrefixPostfix(hash);
                return de.verifyHash(hash, randomSalt, rawContent);
            } else {
                return hash.equals(StringUtil.md5Base16(rawContent));
            }
        }
        return false;
    }

    /**
     * Generate a random salt value if data encryption implementation is exist
     * @return 
     */
    public static String generateRandomSalt() {
        DataEncryption de = getDataEncryption();
        if (de != null) {
            return de.generateRandomSalt();
        }
        return "";
    }
    
    /**
     * Check the content is a wrapped in a security envelop if data encryption 
     * implementation is exist
     * @param content
     * @return 
     */
    public static boolean hasSecurityEnvelope(String content) {
        if (content != null && content.startsWith(ENVELOPE) && content.endsWith(ENVELOPE) && getDataEncryption() != null) {
            return true;
        }
        return false;
    }

    protected static String cleanPrefixPostfix(String content) {
        content = content.replaceAll(ENVELOPE, "");

        return content;
    }
    
    /**
     * Generate a nonce value based on attributes if Nonce Generator implementation is exist
     * @param attributes
     * @param lifepanHour
     * @return 
     */
    public static String generateNonce(String[] attributes, int lifepanHour) {
        
        SetupManager sm = (SetupManager) appContext.getBean("setupManager");
        String extendNonceCacheTime = sm.getSettingValue("extendNonceCacheTime");
        if (extendNonceCacheTime != null && !extendNonceCacheTime.isEmpty()) {
            try {
                lifepanHour += Integer.parseInt(extendNonceCacheTime);
            } catch (Exception e) {}
        }

        if (getNonceGenerator() != null) {
            try {
                return getNonceGenerator().generateNonce(attributes, lifepanHour);
            } catch (Exception e) {
                //Ignore
            }
        }
        return "";
    }
    
    /**
     * Verify the nonce is a valid nonce against the attributes if Nonce 
     * Generator implementation is exist
     * @param nonce
     * @param attributes
     * @return 
     */
    public static boolean verifyNonce(String nonce, String[] attributes) {

        if (nonce != null && !nonce.isEmpty() && getNonceGenerator() != null) {
            try {
                return getNonceGenerator().verifyNonce(nonce, attributes);
            } catch (Exception e) {
                //Ignore
            }
        } else if (getNonceGenerator() == null) { //when nonce is not in use
            return true;
        }
        return false;
    }
    
    /**
     * Clear generated nonces of a request hash when the request hash is submitted 
     * Generator implementation is exist
     * @param requestHash 
     */
    public static void clearNonces(int requestHash) {
        getNonceGenerator().clearNonces(requestHash);
    }

    /**
     * Gets the domain name from a given URL
     * @param url
     * @return 
     */
    public static String getDomainName(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost();
        } catch (Exception e) {}
        return null;
    }
    
    /**
     * Verify the domain name against a whitelist
     * @param domain
     * @param whitelist
     * @return 
     */
    public static boolean isAllowedDomain(String domain, List<String> whitelist) {
        if (whitelist != null && domain != null && domain.contains(", ")) {
            String[] domains = domain.split(", ");
            for (String d : domains) {
                if (whitelist.contains(d)) {
                    return true;
                }
            }
            return false;
        } else {
            return whitelist != null && domain != null && whitelist.contains(domain);
        }
    }

    /**
     * Returns the name of the CRSF token
     * @return 
     */
    public static String getCsrfTokenName() {
        CsrfGuard csrfGuard = CsrfGuard.getInstance();
        return csrfGuard.getTokenName();
    }
    
    /**
     * Returns the value of the CRSF token in the request
     * @param request
     * @return 
     */
    public static String getCsrfTokenValue(HttpServletRequest request) {
        CsrfGuard csrfGuard = CsrfGuard.getInstance();
        if (csrfGuard.getLogicalSessionExtractor() != null) {
            LogicalSession logicalSession = csrfGuard.getLogicalSessionExtractor().extractOrCreate(request);
            return csrfGuard.getTokenService().getTokenValue(logicalSession.getKey(), request.getRequestURI());
        }
        return "";
    }

    /**
     * Validates a boolean String
     * @param input
     * @return
     * @throws IllegalArgumentException if the input is invalid
     */
    public static Boolean validateBooleanInput(Boolean input) throws IllegalArgumentException {
        return input;
    }

    /**
     * Validates an input String
     * @param input
     * @return
     * @throws IllegalArgumentException if the input is invalid
     */
    public static String validateStringInput(String input) throws IllegalArgumentException {
        return validateInput(input, "^[0-9a-zA-Z_\\-\\.\\#\\:]+$");
    }

    /**
     * Validates input
     * @param input
     * @param patternStr
     * @return
     * @throws IllegalArgumentException if the input is invalid
     */
    public static String validateInput(String input, String patternStr) throws IllegalArgumentException {
        Pattern pattern = Pattern.compile(patternStr);
        if (input != null && !input.isEmpty() && !pattern.matcher(input).matches()) {
            throw new IllegalArgumentException("Invalid input: " + input);
        }
        return input;
    }

    public static String normalizedFileName(String filename) {
        // validate input
        try {
            filename = URLDecoder.decode(filename, "UTF-8");
        } catch (Exception ex) {
        }
        
        String normalizedFileName = Normalizer.normalize(filename, Normalizer.Form.NFKC);
        if (normalizedFileName.contains("../") || normalizedFileName.contains("..\\")) {
            throw new SecurityException("Invalid filename " + normalizedFileName);
        }
        
        //handle for commonly used chinese colon char
        if (filename.contains("：")) {
            normalizedFileName = normalizedFileName.replaceAll(":", "：");
        }
        
        return normalizedFileName;
    }
}
