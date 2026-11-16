package com.miyahna.water.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for managing customer information (names, addresses,
 * contact numbers, and delivery metadata) in the local Room database.
 */
@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY createdAt DESC")
    fun observeAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY name ASC")
    fun observeActiveCustomers(): Flow<List<CustomerEntity>>

    @Query(
        """
        SELECT * FROM customers
        WHERE name LIKE '%' || :query || '%'
           OR phone LIKE '%' || :query || '%'
           OR IFNULL(altPhone, '') LIKE '%' || :query || '%'
           OR district LIKE '%' || :query || '%'
           OR addressDetails LIKE '%' || :query || '%'
           OR customerId LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        """
    )
    fun searchCustomers(query: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY createdAt DESC")
    suspend fun getAllCustomersOnce(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getCustomerById(id: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE phone = :phone OR altPhone = :phone LIMIT 1")
    suspend fun getCustomerByPhone(phone: String): CustomerEntity?

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun getCustomersCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomer(customer: CustomerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomers(customers: List<CustomerEntity>)

    @Update
    suspend fun updateCustomer(customer: CustomerEntity): Int

    @Delete
    suspend fun deleteCustomer(customer: CustomerEntity): Int

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteCustomerById(id: String): Int

    @Query("DELETE FROM customers")
    suspend fun clearAllCustomers()
}
