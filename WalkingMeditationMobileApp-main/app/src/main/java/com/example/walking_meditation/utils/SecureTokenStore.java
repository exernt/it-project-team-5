package com.example.walking_meditation.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores bearer credentials encrypted by a non-exportable Android Keystore key. */
final class SecureTokenStore {
    static final String PREFERENCES_NAME = "walking_meditation_session";

    private static final String KEY_ALIAS = "walking_meditation_session_aes_key_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private static final String ACCESS_TOKEN_ENCRYPTED = "access_token_encrypted_v1";
    private static final String REFRESH_TOKEN_ENCRYPTED = "refresh_token_encrypted_v1";
    private static final String LEGACY_ACCESS_TOKEN = "access_token";
    private static final String LEGACY_REFRESH_TOKEN = "refresh_token";

    private SecureTokenStore() {}

    static synchronized void save(
            @NonNull Context context,
            @NonNull String accessToken,
            @NonNull String refreshToken
    ) {
        if (accessToken.isEmpty() && refreshToken.isEmpty()) {
            clear(context);
            return;
        }

        try {
            saveEncrypted(context, accessToken, refreshToken);
        } catch (GeneralSecurityException | IOException firstFailure) {
            resetKeyAndCredentials(context);
            try {
                saveEncrypted(context, accessToken, refreshToken);
            } catch (GeneralSecurityException | IOException secondFailure) {
                resetKeyAndCredentials(context);
                throw new IllegalStateException(
                        "Unable to protect session credentials",
                        secondFailure
                );
            }
        }
    }

    @Nullable
    static synchronized String getAccessToken(@NonNull Context context) {
        return getToken(context, ACCESS_TOKEN_ENCRYPTED);
    }

    @Nullable
    static synchronized String getRefreshToken(@NonNull Context context) {
        return getToken(context, REFRESH_TOKEN_ENCRYPTED);
    }

    static synchronized boolean hasCredentials(@NonNull Context context) {
        String accessToken = getAccessToken(context);
        String refreshToken = getRefreshToken(context);
        return (accessToken != null && !accessToken.isEmpty())
                || (refreshToken != null && !refreshToken.isEmpty());
    }

    static synchronized void clear(@NonNull Context context) {
        preferences(context).edit()
                .remove(ACCESS_TOKEN_ENCRYPTED)
                .remove(REFRESH_TOKEN_ENCRYPTED)
                .remove(LEGACY_ACCESS_TOKEN)
                .remove(LEGACY_REFRESH_TOKEN)
                .commit();
        deleteKeyQuietly();
    }

    @Nullable
    private static String getToken(
            @NonNull Context context,
            @NonNull String encryptedPreference
    ) {
        try {
            SharedPreferences preferences = preferences(context);
            migrateLegacyTokensIfNeeded(context, preferences);
            String encrypted = preferences.getString(encryptedPreference, null);
            if (encrypted == null || encrypted.isEmpty()) {
                return null;
            }
            return decrypt(encrypted);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException
                 | IllegalStateException exception) {
            resetKeyAndCredentials(context);
            return null;
        }
    }

    private static void migrateLegacyTokensIfNeeded(
            @NonNull Context context,
            @NonNull SharedPreferences preferences
    ) {
        if (preferences.contains(ACCESS_TOKEN_ENCRYPTED)
                || preferences.contains(REFRESH_TOKEN_ENCRYPTED)) {
            return;
        }

        String accessToken = preferences.getString(LEGACY_ACCESS_TOKEN, "");
        String refreshToken = preferences.getString(LEGACY_REFRESH_TOKEN, "");
        if ((accessToken == null || accessToken.isEmpty())
                && (refreshToken == null || refreshToken.isEmpty())) {
            return;
        }

        save(
                context,
                accessToken == null ? "" : accessToken,
                refreshToken == null ? "" : refreshToken
        );
    }

    private static void saveEncrypted(
            @NonNull Context context,
            @NonNull String accessToken,
            @NonNull String refreshToken
    ) throws GeneralSecurityException, IOException {
        String encryptedAccessToken = encrypt(accessToken);
        String encryptedRefreshToken = encrypt(refreshToken);
        boolean saved = preferences(context).edit()
                .putString(ACCESS_TOKEN_ENCRYPTED, encryptedAccessToken)
                .putString(REFRESH_TOKEN_ENCRYPTED, encryptedRefreshToken)
                .remove(LEGACY_ACCESS_TOKEN)
                .remove(LEGACY_REFRESH_TOKEN)
                .commit();
        if (!saved) {
            throw new IOException("Unable to persist encrypted session credentials");
        }
    }

    @NonNull
    private static String encrypt(@NonNull String plaintext)
            throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer value = ByteBuffer.allocate(iv.length + ciphertext.length);
        value.put(iv);
        value.put(ciphertext);
        return Base64.encodeToString(value.array(), Base64.NO_WRAP);
    }

    @NonNull
    private static String decrypt(@NonNull String encoded)
            throws GeneralSecurityException, IOException {
        byte[] value = Base64.decode(encoded, Base64.NO_WRAP);
        if (value.length <= GCM_IV_LENGTH_BYTES) {
            throw new GeneralSecurityException("Encrypted credential is malformed");
        }
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        byte[] ciphertext = new byte[value.length - GCM_IV_LENGTH_BYTES];
        System.arraycopy(value, 0, iv, 0, iv.length);
        System.arraycopy(value, iv.length, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        );
        return new String(
                cipher.doFinal(ciphertext),
                java.nio.charset.StandardCharsets.UTF_8
        );
    }

    @NonNull
    private static SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
        );
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGenerator.generateKey();
    }

    private static void resetKeyAndCredentials(@NonNull Context context) {
        preferences(context).edit()
                .remove(ACCESS_TOKEN_ENCRYPTED)
                .remove(REFRESH_TOKEN_ENCRYPTED)
                .remove(LEGACY_ACCESS_TOKEN)
                .remove(LEGACY_REFRESH_TOKEN)
                .commit();
        deleteKeyQuietly();
    }

    private static void deleteKeyQuietly() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (GeneralSecurityException | IOException ignored) {
            // Credentials have already been removed from app storage.
        }
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }
}
