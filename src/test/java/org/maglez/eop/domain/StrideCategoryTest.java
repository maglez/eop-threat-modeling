package org.maglez.eop.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("STRIDE Categories")
class StrideCategoryTest {

    @Test
    void shouldHaveAllSixCategories() {
        StrideCategory[] values = StrideCategory.values();
        assertEquals(6, values.length);
    }

    @Test
    void shouldContainElevationOfPrivilege() {
        assertNotNull(StrideCategory.valueOf("ELEVATION_OF_PRIVILEGE"));
    }

    @Test
    void shouldContainSpoofing() {
        assertNotNull(StrideCategory.valueOf("SPOOFING"));
    }

    @Test
    void shouldContainTampering() {
        assertNotNull(StrideCategory.valueOf("TAMPERING"));
    }

    @Test
    void shouldContainRepudiation() {
        assertNotNull(StrideCategory.valueOf("REPUDIATION"));
    }

    @Test
    void shouldContainInformationDisclosure() {
        assertNotNull(StrideCategory.valueOf("INFORMATION_DISCLOSURE"));
    }

    @Test
    void shouldContainDenialOfService() {
        assertNotNull(StrideCategory.valueOf("DENIAL_OF_SERVICE"));
    }

    @Test
    @DisplayName("declaration order is the STRIDE acronym order, because deckOrder derives from it")
    void shouldDeclareCategoriesInAcronymOrder() {
        assertArrayEquals(
                new StrideCategory[] {
                    StrideCategory.SPOOFING,
                    StrideCategory.TAMPERING,
                    StrideCategory.REPUDIATION,
                    StrideCategory.INFORMATION_DISCLOSURE,
                    StrideCategory.DENIAL_OF_SERVICE,
                    StrideCategory.ELEVATION_OF_PRIVILEGE,
                },
                StrideCategory.values());
    }

    @Test
    @DisplayName("deckOrder is one-based so a missing value cannot masquerade as the first suit")
    void shouldNumberDeckOrderFromOne() {
        assertEquals(1, StrideCategory.SPOOFING.deckOrder());
        assertEquals(6, StrideCategory.ELEVATION_OF_PRIVILEGE.deckOrder());
        for (StrideCategory category : StrideCategory.values()) {
            assertEquals(category.ordinal() + 1, category.deckOrder());
        }
    }
}
