package source.kfe.rail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LndRestLightningClientPaymentTest {

    @Test
    void parsesFixedBolt11AmountsFromHrp() {
        // 1 mBTC = 100_000 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb1m1p49n88hpp5j50pdjyzl5rvxh6v6f28lzhltvswwkrvpyp93zlht0d"))
                .isEqualTo(100_000L);
        // 800 uBTC = 80_000 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb800u1p49jmrupp5rscdcktt046suhys2hxfhf7n07lu68sgcfu2ss8md"))
                .isEqualTo(80_000L);
        // 200 uBTC = 20_000 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb200u1p49jmt6pp5wat3awf2c7clcz68vlpvfs9gfzp20fh5wu3yshkre"))
                .isEqualTo(20_000L);
        // 306_610 nBTC = 30_661 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb306610n1p49jm3dpp56whkqghslwlm9x3930u7j427ldd6j3adwrfxz5"))
                .isEqualTo(30_661L);
    }

    @Test
    void amountlessBolt11HasZeroHrpAmount() {
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb1p49n88hpp5j50pdjyzl5rvxh6v6f28lzhltvswwkrvpyp93zlht0d"))
                .isZero();
    }

    @Test
    void classifiesPermanentLightningErrors() {
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "invoice expired. Valid until 2026-07-16"))
                .isTrue();
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "amount must not be specified when paying a non-zero amount invoice"))
                .isTrue();
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "connection refused"))
                .isFalse();
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "payment attempt not completed before timeout"))
                .isFalse();
    }
}
