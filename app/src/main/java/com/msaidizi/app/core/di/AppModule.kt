package com.msaidizi.app.core.di

import android.content.Context
import androidx.room.Room
import com.msaidizi.app.core.database.*
import com.msaidizi.app.core.security.EncryptionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        encryptionManager: EncryptionManager
    ): MsaidiziDatabase {
        val passphrase = encryptionManager.getDatabasePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            MsaidiziDatabase::class.java,
            "msaidizi.db"
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideSaleDao(db: MsaidiziDatabase): SaleDao = db.saleDao()
    @Provides fun provideProductDao(db: MsaidiziDatabase): ProductDao = db.productDao()
    @Provides fun provideExpenseDao(db: MsaidiziDatabase): ExpenseDao = db.expenseDao()
    @Provides fun provideCustomerDao(db: MsaidiziDatabase): CustomerDao = db.customerDao()
    @Provides fun provideDailySummaryDao(db: MsaidiziDatabase): DailySummaryDao = db.dailySummaryDao()
    @Provides fun provideStockMovementDao(db: MsaidiziDatabase): StockMovementDao = db.stockMovementDao()
    @Provides fun provideConversationDao(db: MsaidiziDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideKnowledgeDao(db: MsaidiziDatabase): KnowledgeDao = db.knowledgeDao()
    @Provides fun provideUserProfileDao(db: MsaidiziDatabase): UserProfileDao = db.userProfileDao()
}
