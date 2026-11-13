package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator();

    @ParameterizedTest
    @CsvSource({
        "regular, 100.00", "vip, 90.00", "wholesale, 85.00", "VIP, 100.00", "unknown, 100.00"
    })
    void appliesOnlyRecognizedCustomerDiscounts(String customerType, String expected) {
        assertTotal("100", 1, customerType, null, expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void treatsMissingCustomerTypeAsRegular(String customerType) {
        assertTotal("100", 1, customerType, null, "100.00");
    }

    @ParameterizedTest
    @CsvSource({"9, 90.00", "10, 100.00", "11, 104.50"})
    void appliesBulkDiscountStrictlyAboveTenUnitsPerLine(int quantity, String expected) {
        assertTotal("10", quantity, "regular", null, expected);
    }

    @Test
    void doesNotCombineQuantitiesFromDifferentLinesForBulkDiscount() {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line("10", 6), line("10", 5)), "regular", null);

        assertThat(total).isEqualByComparingTo("110.00");
    }

    @ParameterizedTest
    @CsvSource({"SAVE10, 90.00", "SAVE20PERCENT, 80.00", "save10, 100.00", "unknown, 100.00"})
    void appliesOnlyRecognizedCoupons(String coupon, String expected) {
        assertTotal("100", 1, "regular", coupon, expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void ignoresMissingCoupon(String coupon) {
        assertTotal("100", 1, "regular", coupon, "100.00");
    }

    @ParameterizedTest
    @CsvSource({"0, 0.00", "9.99, 0.00", "10, 0.00", "10.01, 0.01"})
    void neverReturnsNegativeTotalAfterFixedCoupon(String price, String expected) {
        assertTotal(price, 1, "regular", "SAVE10", expected);
    }

    @ParameterizedTest
    @CsvSource({"999.99, 999.99", "1000, 1000.00", "1000.01, 980.01", "2000, 1960.00"})
    void discountsRatherThanCapsTotalsStrictlyAboveOneThousand(String price, String expected) {
        assertTotal(price, 1, "regular", null, expected);
    }

    @ParameterizedTest
    @CsvSource({"1001, vip, SAVE10, 890.90", "1250, regular, SAVE20PERCENT, 1000.00",
        "1250.01, regular, SAVE20PERCENT, 980.01", "1000, wholesale, SAVE10, 840.00"})
    void checksLargeOrderThresholdAfterCustomerDiscountAndCoupon(
            String price, String customerType, String coupon, String expected) {
        assertTotal(price, 1, customerType, coupon, expected);
    }

    @Test
    void combinesAllDiscountsInTheirOriginalOrder() {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line("100", 11), line("20", 2)), "vip", "SAVE20PERCENT");

        assertThat(total).isEqualByComparingTo("781.20");
    }

    @ParameterizedTest
    @CsvSource({"1.004, 1.00", "1.005, 1.01", "1.006, 1.01"})
    void roundsHalfUpToTwoDecimalPlaces(String price, String expected) {
        BigDecimal total = calculator.calculateOrderTotal(List.of(line(price, 1)), "regular", null);

        assertThat(total).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void roundsOnlyAfterSummingLinesAndApplyingDiscounts() {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line("0.005", 1), line("0.005", 1)), "vip", null);

        assertThat(total).isEqualTo(new BigDecimal("0.01"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"SAVE10", "SAVE20PERCENT"})
    void returnsScaledZeroForEmptyOrder(String coupon) {
        assertThat(calculator.calculateOrderTotal(List.of(), "regular", coupon))
                .isEqualTo(new BigDecimal("0.00"));
    }

    @ParameterizedTest
    @CsvSource({"10, 0, 0.00", "10, -1, 0.00", "-10, 1, 0.00", "-10, -2, 20.00"})
    void characterizesUnvalidatedQuantitiesAndPrices(String price, int quantity, String expected) {
        assertTotal(price, quantity, "regular", null, expected);
    }

    @Test
    void rejectsNullItemList() {
        assertThatNullPointerException().isThrownBy(() -> calculator.calculateOrderTotal(null, null, null));
    }

    @Test
    void rejectsNullItem() {
        assertThatNullPointerException().isThrownBy(() -> calculator.calculateOrderTotal(
                Arrays.asList((PricingCalculator.LineItem) null), null, null));
    }

    @Test
    void rejectsNullPrice() {
        assertThatNullPointerException().isThrownBy(() -> calculator.calculateOrderTotal(
                List.of(new PricingCalculator.LineItem(null, 1)), null, null));
    }

    private void assertTotal(String price, int quantity, String customerType, String coupon, String expected) {
        assertThat(calculator.calculateOrderTotal(List.of(line(price, quantity)), customerType, coupon))
                .isEqualTo(new BigDecimal(expected));
    }

    private static PricingCalculator.LineItem line(String price, int quantity) {
        return new PricingCalculator.LineItem(new BigDecimal(price), quantity);
    }
}
