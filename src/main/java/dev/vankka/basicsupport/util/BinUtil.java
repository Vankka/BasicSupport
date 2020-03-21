package dev.vankka.basicsupport.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Credits to https://github.com/DiscordSRV/DiscordSRV/blob/master/src/main/java/github/scarsz/discordsrv/util/DebugUtil.java
 * GPLv3
 * @author Scarsz (github.com/Scarsz)
 *
 * Changes:
 * - Different files
 * - Uses pure Java for http & org.json instead of GSON
 * - Everything except the main method is now private
 * @author Vankka (github.com/Vankka)
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BinUtil {

    public static String createReport(String authorName, Set<String> participants, String transcript, String binUrl) {
        List<Map<String, String>> files = new LinkedList<>();

        files.add(fileMap("ticket-info.txt", "Information about this ticket",
                "Ticket author: " + authorName + "\n" +
                        "\n" +
                        "Ticket participants:\n" +
                        String.join("\n", participants)
        ));
        files.add(fileMap("trascript.txt", "The discussion within the ticket", transcript));

        try {
            return uploadToBin(binUrl, files);
        } catch (Exception e) {
            e.printStackTrace();
            return "Bin upload failed: " + e.toString();
        }
    }

    private static String uploadToBin(String binUrl, List<Map<String, String>> files) {
        String key = RandomStringUtils.randomAlphanumeric(32);
        byte[] keyBytes = key.getBytes();

        // decode to bytes, encrypt, base64
        for (Map<String, String> file : files) {
            file.entrySet().removeIf(entry -> StringUtils.isBlank(entry.getValue()));
            file.replaceAll((k, v) -> b64(encrypt(keyBytes, file.get(k))));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("description", b64(encrypt(keyBytes, "Support ticket transcript")));
        payload.put("expiration", TimeUnit.DAYS.toMinutes(14));
        payload.put("files", files);

        String rawData = new JSONObject(payload).toString();

        try {
            URL url = new URL(binUrl + "/v1/post");

            HttpsURLConnection httpsURLConnection = (HttpsURLConnection) url.openConnection();
            httpsURLConnection.setRequestProperty("User-Agent", "BasicSupport 1.0");
            httpsURLConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            httpsURLConnection.setRequestProperty("Content-Length", String.valueOf(rawData.length()));
            httpsURLConnection.setRequestMethod("POST");
            httpsURLConnection.setDoOutput(true);

            try (BufferedOutputStream outputStream = new BufferedOutputStream(httpsURLConnection.getOutputStream())) {
                outputStream.write(rawData.getBytes());
                outputStream.flush();
            }

            StringWriter stringWriter = new StringWriter();
            try (InputStream inputStream = new BufferedInputStream(httpsURLConnection.getInputStream())) {
                int bit;

                while ((bit = inputStream.read()) != -1) {
                    stringWriter.write(bit);
                }
            }

            int responseCode = httpsURLConnection.getResponseCode();
            if (responseCode == 200) {
                JSONObject response = new JSONObject(stringWriter.toString());
                if (response.getString("status").equals("ok")) {
                    return binUrl + "/" + response.getString("bin") + "#" + key;
                } else {
                    return "Bin upload failed: " + response.optString("error", "no error");
                }
            } else {
                return "Bin upload returned bad HTTP status: " + responseCode;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "Bin upload failed: " + e.toString();
        }
    }

    private static Map<String, String> fileMap(String name, String description, String content) {
        Map<String, String> map = new HashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("content", content);
        map.put("type", "text/plain");
        return map;
    }

    public static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * Encrypt the given `data` UTF-8 String with the given `key` (16 bytes, 128-bit)
     * @param key the key to encrypt data with
     * @param data the UTF-8 string to encrypt
     * @return the randomly generated IV + the encrypted data with no separator ([iv..., encryptedData...])
     */
    public static byte[] encrypt(byte[] key, String data) {
        return encrypt(key, data.getBytes(StandardCharsets.UTF_8));
    }

    private static final Random SECURE_RANDOM = new SecureRandom();

    /**
     * Encrypt the given `data` byte array with the given `key` (16 bytes, 128-bit)
     * @param key the key to encrypt data with
     * @param data the data to encrypt
     * @return the randomly generated IV + the encrypted data with no separator ([iv..., encryptedData...])
     */
    public static byte[] encrypt(byte[] key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
            byte[] iv = new byte[cipher.getBlockSize()];
            SECURE_RANDOM.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(data);
            return ArrayUtils.addAll(iv, encrypted);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }
}
