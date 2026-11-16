package com.miyahna.water

import com.miyahna.water.data.local.CustomerEntity
import com.miyahna.water.data.local.toDomain
import com.miyahna.water.data.local.toEntity
import com.miyahna.water.domain.Customer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerEntityUnitTest {
    @Test
    fun customerRoundTripPreservesNameAddressAndContactNumbers() {
        val domainCustomer = Customer(
            id = "cust-101",
            customerId = "CUST-0007",
            name = "سالم الجلال",
            phone = "771234567",
            altPhone = "733998877",
            district = "العرقين",
            addressDetails = "قرب محطة الوادي - البيت الأبيض",
            lat = 15.4955,
            lng = 45.3672,
            tankCapacityLiters = 4000,
            tankRemainingLiters = 1200,
            notes = "التوصيل صباحاً",
            createdAt = "2026-03-10T09:00:00Z",
            isActive = true,
            totalOrdersCount = 5,
            totalLitersDelivered = 18000,
            isOfflineCreated = true
        )

        val entity: CustomerEntity = domainCustomer.toEntity()
        assertEquals("cust-101", entity.id)
        assertEquals("CUST-0007", entity.customerId)
        assertEquals("سالم الجلال", entity.name)
        assertEquals("771234567", entity.phone)
        assertEquals("733998877", entity.altPhone)
        assertEquals("العرقين", entity.district)
        assertEquals("قرب محطة الوادي - البيت الأبيض", entity.addressDetails)
        assertTrue(entity.isOfflineCreated)

        val restored = entity.toDomain()
        assertEquals(domainCustomer, restored)
    }

    @Test
    fun customerEntityDefaultsNullableFieldsCleanly() {
        val entity = CustomerEntity(
            id = "cust-202",
            customerId = "CUST-0008",
            name = "علي محمد",
            phone = "770000111",
            district = "مديرية الوادي",
            lat = 15.4920,
            lng = 45.3620,
            createdAt = "2026-03-11T10:00:00Z"
        )

        val domain = entity.toDomain()
        assertEquals(null, domain.altPhone)
        assertEquals("", domain.addressDetails)
        assertEquals(2000, domain.tankCapacityLiters)
        assertTrue(domain.isActive)
        assertFalse(domain.isOfflineCreated)
    }
}
