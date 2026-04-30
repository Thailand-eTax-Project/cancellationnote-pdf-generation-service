package com.wpanther.cancellationnote.pdf.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class FontHealthCheckTest {

    private FontHealthCheck createHealthCheck(boolean failOnError) throws Exception {
        FontHealthCheck check = new FontHealthCheck();
        Field failOnErrorField = FontHealthCheck.class.getDeclaredField("failOnError");
        failOnErrorField.setAccessible(true);
        failOnErrorField.set(check, failOnError);
        return check;
    }

    @Test
    void checkFontsAtStartup_allFontsPresent_succeeds() throws Exception {
        FontHealthCheck check = createHealthCheck(true);
        assertThatNoException().isThrownBy(check::checkFontsAtStartup);
    }

    @Test
    void checkFontsAtStartup_failOnErrorFalse_continuesWhenFontsMissing() throws Exception {
        FontHealthCheck check = createHealthCheck(false);
        assertThatNoException().isThrownBy(check::checkFontsAtStartup);
    }

    @Test
    void checkFontsAtStartup_failOnErrorTrue_doesNotThrowWhenFontsPresent() throws Exception {
        FontHealthCheck check = createHealthCheck(true);
        assertThatNoException().isThrownBy(check::checkFontsAtStartup);
    }

    @Test
    void requiredFontsArray_containsExpectedFonts() throws Exception {
        Field field = FontHealthCheck.class.getDeclaredField("REQUIRED_FONTS");
        field.setAccessible(true);
        String[] fonts = (String[]) field.get(null);
        assertThat(fonts).hasSize(6);
        assertThat(fonts).contains(
                "fonts/THSarabunNew.ttf",
                "fonts/THSarabunNew-Bold.ttf",
                "fonts/THSarabunNew-Italic.ttf",
                "fonts/THSarabunNew-BoldItalic.ttf",
                "fonts/NotoSansThaiLooped-Regular.ttf",
                "fonts/NotoSansThaiLooped-Bold.ttf"
        );
    }
}