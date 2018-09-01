package com.microsoft.intellij.runner.webapp

import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.appservice.*
import com.microsoft.azuretools.authmanage.AuthMethodManager
import com.microsoft.azuretools.core.mvp.model.webapp.AzureWebAppMvpModel
import com.microsoft.azuretools.core.mvp.ui.base.SchedulerProviderFactory
import rx.Observable

object AzureDotNetWebAppMvpModel {

    //region Web App

    fun createWebAppWithNewAppServicePlan(subscriptionId: String,
                                          webAppName: String,
                                          appServicePlanName: String,
                                          pricingTier: PricingTier,
                                          region: String,
                                          isCreatingResourceGroup: Boolean,
                                          resourceGroupName: String): WebApp {
        val azure = AuthMethodManager.getInstance().getAzureClient(subscriptionId)
        return webAppWithNewAppServicePlan(azure, webAppName, appServicePlanName, pricingTier, region, isCreatingResourceGroup, resourceGroupName).create()
    }

    fun createWebAppWithExistingAppServicePlan(subscriptionId: String,
                                               webAppName: String,
                                               appServicePlanId: String,
                                               isCreatingResourceGroup: Boolean,
                                               resourceGroupName: String): WebApp {

        val azure = AuthMethodManager.getInstance().getAzureClient(subscriptionId)
        return webAppWithExistingAppServicePlan(azure, webAppName, appServicePlanId, isCreatingResourceGroup, resourceGroupName)
                .create()
    }

    fun getDotNetWebApp(subscriptionId: String, webAppId: String): WebApp {
        return AzureWebAppMvpModel.getInstance().getWebAppById(subscriptionId, webAppId)
    }

    //endregion Web App

    //region Check Existence

    /**
     * Check an Azure Resource Group name existence over azure portal
     *
     * Note: Method should be used in configuration validation logic.
     *       Suppress for now, because current configuration validation mechanism does not allow to easily make async call for validation
     */
    @Suppress("unused")
    fun checkResourceGroupExistence(subscriptionId: String, resourceGroupName: String): Boolean {
        val azure = AuthMethodManager.getInstance().getAzureClient(subscriptionId)
        return azure.resourceGroups().contain(resourceGroupName)
    }

    //endregion Check Existence

    //region Private Methods and Operators

    private fun webAppWithNewAppServicePlan(azure: Azure,
                                            webAppName: String,
                                            appServicePlanName: String,
                                            pricingTier: PricingTier,
                                            region: String,
                                            isCreatingResourceGroup: Boolean,
                                            resourceGroupName: String) : WebApp.DefinitionStages.WithCreate {

        // App Service Plan
        val planWithRegion = azure.appServices().appServicePlans()
                .define(appServicePlanName)
                .withRegion(region)

        val planWithGroup =
                if (isCreatingResourceGroup) planWithRegion.withNewResourceGroup(resourceGroupName)
                else planWithRegion.withExistingResourceGroup(resourceGroupName)

        val plan = planWithGroup
                .withPricingTier(pricingTier)
                .withOperatingSystem(OperatingSystem.WINDOWS)

        // Web App
        val appWithRegion = azure.webApps().define(webAppName).withRegion(region)

        val appWithGroup =
                if (isCreatingResourceGroup) appWithRegion.withNewResourceGroup(resourceGroupName)
                else appWithRegion.withExistingResourceGroup(resourceGroupName)

        return appWithGroup.withNewWindowsPlan(plan)
    }

    private fun webAppWithExistingAppServicePlan(azure: Azure,
                                                 webAppName: String,
                                                 appServicePlanId: String,
                                                 isCreatingResourceGroup: Boolean,
                                                 resourceGroupName: String) : WebApp.DefinitionStages.WithCreate {

        val plan = azure.appServices().appServicePlans().getById(appServicePlanId)

        val appWithExistingPlan = azure.webApps().define(webAppName).withExistingWindowsPlan(plan)

        return if (isCreatingResourceGroup) appWithExistingPlan.withNewResourceGroup(resourceGroupName)
        else appWithExistingPlan.withExistingResourceGroup(resourceGroupName)
    }

    //endregion Private Methods and Operators
}
