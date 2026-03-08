package com.extractor.auth;

import com.google.auth.oauth2.GoogleCredentials;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for GoogleAuthModule.
 * Validates: Requirements 5.2
 */
class GoogleAuthModuleTest {

    /**
     * Minimal valid service account JSON for testing.
     * Uses a generated-for-test-only RSA private key — no real credentials.
     */
    private static final String VALID_SERVICE_ACCOUNT_JSON = """
            {
              "type": "service_account",
              "project_id": "test-project",
              "private_key_id": "key-id-123",
              "private_key": "-----BEGIN PRIVATE KEY-----\\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCs00c02sUytPSZ\\nzIFhov2gfSmLwr1bnxpV3m3UCglcA/qLBF8geNMp6F2qIH+wuwb5uQBoyRKSS4Sp\\nEajN4QvfNjNaHzZ1TIAA618wafR4+e/st+NAAfKmv4fB24nj9M8G0GlB3lWUp6K0\\nCusJqWqay6VypdJ21uTTvgSXrTQXBMhhnMHv4i4T8DxSAnz87XE9jteqMqvRQSnH\\nx0ke1RXvOyEk0xAbSecv4eRIiCkYhMrxzW6aVu65cDxTuZN3fsj46rr5/j3wao1x\\nvizm96sSJ1TQbKuitAVeXXoBN/1mGo+oCWk/ulRCmfRNWFu48YiMFulnG+eSqJw6\\ni3GEldpFAgMBAAECggEAcevTREPxOTuPANKNdo66INBgUoBL0dlOwsucGemDwePd\\ng0Webwk2KKnDFCHYCec+8d3BJ1FjrIubJtc9LnjqGnjf4MgyXZ/PwMtmM8OkIxqd\\nzKxqYAborIIUOvU8L8dvsE4xE/o674KQ/Z000Wmbm+4hYTFtSmjc7baT0Gebro+j\\nIhrhTOESCBkckr692WVAwsOccFFFE3ALXz0jgT/Mt3snneLJDLTSdjAcRN49XxiS\\nHb1Aw8PV5y4rddxLdE52JsfW0rulCJSgko8wbRTpalH4bv014fRSjkSqbOsf1Ey6\\nHufIpDJVyZDUUkxFTm/lYi5MTC1FUqTWeh9cPoiwAQKBgQDXtgpbg+iGWnGYB06R\\n2TjqlEg/0vAYIVlKhT6rdzhR8aP1LKWqeUsxyrt2wDMY8JYhfwkw13/H0CNgTzDi\\nDj9Un1/wi//4JwasBrb28sHj7kQ2kAhjUXDxl8phSOJrlcI6nKLKqEyFTNVvhUz1\\njC5sXKXEvjlF5rmSO4N1wRmk0QKBgQDNGrTkesCVRrPmZvxCOd+bSInql0YNl1GA\\nu9ifj4KDkXR5kbc3dgN0SXLtyZjv70ro14721WWZ84Qe7t7G7jbb84iHhcbzFUeS\\nTMidg4nXEtkNLd6Qkj6YHy8D8ju0VHFHIfSbfIgDUnUTjdMg/cxI1K6v4W7aj55k\\ndKlqVIXLNQKBgQDFnxBEIUgA9pFFL8SKmBCt0GWUm2K9KKhOPl5Y5mNhp1iHgHzR\\n/kemeU5fj9wASEGytFiuN2/olkYisMpe/6CDnXNexFQge1iAd7Jd8a8ya5Jwzmnw\\n26b2nxKZKBXPbKdB3UjDE4bvRKHxhpUoDGJngkWYRpHf+W4fi4h3dA5TUQKBgDBj\\nyqXVKDaP1cXYKk/do5nZRWCpjAeM2dfLedI7Y4ly+c3syRM2rp3y7kCXvZNuG3hP\\n0xT9R9lIkLVTmg/JB/xguqdusn+LV7V2lCZFcHHjqn3ngokNV+NACDGgJuHmwksM\\nAc/lU5mlDKYuYc940YelVgl84FCEbdQ5ifbiNia5AoGBAI3pFA8a0NhSMKUdTknr\\nHJoTFk++pfNTN/Yz1qiRlGpWmAwKlCjhDQ8tkgBLcXxk5EJiapZRvbaDdj10csdl\\nI8HqVLlqwR3ZtZSU/8xasR6V4pzKYcSLkN9iSe4TbDl45u/54zAPTwhRhXzh/c4X\\ntDvVd90BHT7XSqSB9pqK3mg7\\n-----END PRIVATE KEY-----\\n",
              "client_email": "test@test-project.iam.gserviceaccount.com",
              "client_id": "123456789",
              "auth_uri": "https://accounts.google.com/o/oauth2/auth",
              "token_uri": "https://oauth2.googleapis.com/token"
            }
            """;

    @Test
    void constructor_parsesValidServiceAccountJson() {
        var module = new GoogleAuthModule(VALID_SERVICE_ACCOUNT_JSON);

        assertThat(module.getCredentials()).isNotNull();
    }

    @Test
    void getCredentials_returnsGoogleCredentials() {
        var module = new GoogleAuthModule(VALID_SERVICE_ACCOUNT_JSON);
        var credentials = module.getCredentials();

        assertThat(credentials).isInstanceOf(GoogleCredentials.class);
    }

    @Test
    void getCredentials_returnsSameInstanceOnMultipleCalls() {
        var module = new GoogleAuthModule(VALID_SERVICE_ACCOUNT_JSON);

        var first = module.getCredentials();
        var second = module.getCredentials();

        assertThat(first).isSameAs(second);
    }

    @Test
    void constructor_throwsOnNullJson() {
        assertThatThrownBy(() -> new GoogleAuthModule(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    void constructor_throwsOnBlankJson() {
        assertThatThrownBy(() -> new GoogleAuthModule("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    void constructor_throwsOnEmptyJson() {
        assertThatThrownBy(() -> new GoogleAuthModule(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    void constructor_throwsOnInvalidJson() {
        assertThatThrownBy(() -> new GoogleAuthModule("{ not valid json }"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Google service account credentials");
    }

    @Test
    void constructor_throwsOnJsonMissingRequiredFields() {
        var incompleteJson = """
                {
                  "type": "service_account",
                  "project_id": "test-project"
                }
                """;

        assertThatThrownBy(() -> new GoogleAuthModule(incompleteJson))
                .isInstanceOf(RuntimeException.class);
    }
}
