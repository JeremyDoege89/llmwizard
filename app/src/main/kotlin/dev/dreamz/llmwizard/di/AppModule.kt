package dev.dreamz.llmwizard.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dreamz.llmwizard.conn.Servers
import dev.dreamz.llmwizard.core.catalog.Catalog
import dev.dreamz.llmwizard.data.AppDb
import dev.dreamz.llmwizard.data.KeyVault
import dev.dreamz.llmwizard.data.Settings
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): AppDb = Room.databaseBuilder(c, AppDb::class.java, "llmwizard.db").build()

    @Provides @Singleton fun keyVault(@ApplicationContext c: Context) = KeyVault(c)
    @Provides @Singleton fun settings(@ApplicationContext c: Context) = Settings(c)
    @Provides @Singleton fun catalog(): Catalog = Catalog.bundled()
    @Provides @Singleton fun servers(db: AppDb, vault: KeyVault) = Servers(db, vault)
}
