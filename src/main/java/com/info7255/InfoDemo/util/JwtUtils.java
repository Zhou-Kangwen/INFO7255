package com.info7255.InfoDemo.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.util.StringUtils;
import sun.security.util.DerInputStream;
import sun.security.util.DerValue;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

public class JwtUtils {

    public static final String DEVICE_ID = "device_id";
    public static final long EXPIRE = 10 * 60 * 60;

//    public static void main(String[] args) throws Exception {
//        String token = createAccessJwtToken("YJ1001");
//        System.err.println(token);
//        boolean validatorToken = validatorToken(token);
//        System.err.println(validatorToken);
//        String id = parseAccessJwtToken(token);
//        System.err.println(id);
//    }

    /**
     * 生成token字符串的方法
     *
     * @param deviceId
     * @return
     */
    public static String createAccessJwtToken(String deviceId) {
        PrivateKey privateKey = getRSAPrivateKey();

        Claims claims = Jwts.claims().setSubject(deviceId);
        claims.put(DEVICE_ID, deviceId);

        String accessToken = Jwts.builder()
                .setClaims(claims)
                .setIssuer("User")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRE))
                .signWith(SignatureAlgorithm.ES256, privateKey)
                .compact();

        return accessToken;
    }

    /**
     * 判断token是否存在与有效
     *
     * @param accessToken
     * @return
     */
    public static boolean validatorToken(String accessToken) {
        try {
            if (StringUtils.isEmpty(accessToken)) {
                return false;
            }
            PublicKey publicKey = getRSAPublicKey();
            Jwts.parser().setSigningKey(publicKey).parseClaimsJws(accessToken);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 根据token获取deviceId
     *
     * @param accessToken
     * @return
     */
    public static String parseAccessJwtToken(String accessToken) {
        try {
            if (StringUtils.isEmpty(accessToken)) {
                return null;
            }
            PublicKey publicKey = getRSAPublicKey();
            Jws<Claims> jws = Jwts.parser().setSigningKey(publicKey).parseClaimsJws(accessToken);
            Claims claims = jws.getBody();
            return claims.get(DEVICE_ID, String.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取PrivateKey对象
     *
     * @return
     */
    private static PrivateKey getRSAPrivateKey() {
        try {
            InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("crt/rsa_private.pem");
            String privateKeyBase64 = IOUtils.toString(inputStream, "UTF-8");
            String privateKeyPEM = privateKeyBase64.replaceAll("\\-*BEGIN.*KEY\\-*", "")
                    .replaceAll("\\-*END.*KEY\\-*", "")
                    .replaceAll("\r", "")
                    .replaceAll("\n", "");
            byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
            DerInputStream derReader = new DerInputStream(encoded);
            DerValue[] seq = derReader.getSequence(0);
            if (seq.length < 9) {
                throw new GeneralSecurityException("Could not read private key");
            }
            // skip version seq[0];
            BigInteger modulus = seq[1].getBigInteger();
            BigInteger publicExp = seq[2].getBigInteger();
            BigInteger privateExp = seq[3].getBigInteger();
            BigInteger primeP = seq[4].getBigInteger();
            BigInteger primeQ = seq[5].getBigInteger();
            BigInteger expP = seq[6].getBigInteger();
            BigInteger expQ = seq[7].getBigInteger();
            BigInteger crtCoeff = seq[8].getBigInteger();
            RSAPrivateCrtKeySpec keySpec = new RSAPrivateCrtKeySpec(modulus, publicExp, privateExp, primeP, primeQ, expP, expQ, crtCoeff);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePrivate(keySpec);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取PublicKey对象
     *
     * @return
     */
    private static PublicKey getRSAPublicKey() {
        try {
            InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("crt/rsa_public.pem");
            String publicKeyBase64 = IOUtils.toString(inputStream, "UTF-8");
            String publicKeyPEM = publicKeyBase64.replaceAll("\\-*BEGIN.*KEY\\-*", "")
                    .replaceAll("\\-*END.*KEY\\-*", "")
                    .replaceAll("\r", "")
                    .replaceAll("\n", "");
            Security.addProvider(new BouncyCastleProvider());
            X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyPEM));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(pubKeySpec);
            return publicKey;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}

