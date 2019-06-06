/*
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.microsoft.azuretools.core.mvp.model.webapp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.microsoft.azure.management.appservice.*;
import org.apache.commons.io.IOUtils;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.Subscription;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azuretools.authmanage.AuthMethodManager;
import com.microsoft.azuretools.azurecommons.helpers.NotNull;
import com.microsoft.azuretools.core.mvp.model.AzureMvpModel;
import com.microsoft.azuretools.core.mvp.model.ResourceEx;
import com.microsoft.azuretools.utils.WebAppUtils;

public class AzureWebAppMvpModel {

    public static final String CANNOT_GET_WEB_APP_WITH_ID = "Cannot get Web App with ID: ";
    private final Map<String, List<ResourceEx<WebApp>>> subscriptionIdToWebApps;

    private AzureWebAppMvpModel() {
        subscriptionIdToWebApps = new ConcurrentHashMap<>();
    }

    public static AzureWebAppMvpModel getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * get the web app by ID.
     */
    public WebApp getWebAppById(String sid, String id) throws Exception {
        Azure azure = AuthMethodManager.getInstance().getAzureClient(sid);
        WebApp app = azure.webApps().getById(id);
        if (app == null) {
            throw new Exception(CANNOT_GET_WEB_APP_WITH_ID + id); // TODO: specify the type of exception.
        }
        return app;
    }

    /**
     * API to create new Web App by setting model.
     */
    public WebApp createWebApp(@NotNull WebAppSettingModel model) throws Exception {
        switch (model.getOS()) {
            case WINDOWS:
                return createWebAppOnWindows(model);
            case LINUX:
                return createWebAppOnLinux(model);
            default:
                throw new Exception("Invalid operating system setting: " + model.getOS());
        }
    }

    /**
     * API to create a new Deployment Slot by setting model.
     */
    public DeploymentSlot createDeploymentSlot(@NotNull WebAppSettingModel model) throws Exception {
        final WebApp app = getWebAppById(model.getSubscriptionId(), model.getWebAppId());
        final String name = model.getNewSlotName();
        final String configurationSource = model.getNewSlotConfigurationSource();
        final DeploymentSlot.DefinitionStages.Blank definedSlot = app.deploymentSlots().define(name);

        if (configurationSource.equals(app.name())) {
            return definedSlot.withConfigurationFromParent().create();
        }

        final DeploymentSlot configurationSourceSlot = app.deploymentSlots()
            .list()
            .stream()
            .filter(s -> configurationSource.equals(s.name()))
            .findAny()
            .orElse(null);

        if (configurationSourceSlot != null) {
            return definedSlot.withConfigurationFromDeploymentSlot(configurationSourceSlot).create();
        } else {
            return definedSlot.withBrandNewConfiguration().create();
        }
    }

     /**
     * API to create Web App on Windows .
     *
     * @param model parameters
     * @return instance of created WebApp
     * @throws Exception exception
     */
    public WebApp createWebAppOnWindows(@NotNull WebAppSettingModel model) throws Exception {
        Azure azure = AuthMethodManager.getInstance().getAzureClient(model.getSubscriptionId());

        WebApp.DefinitionStages.WithCreate withCreate;
        if (model.isCreatingAppServicePlan()) {
            withCreate = withCreateNewWindowsServicePlan(azure, model);
        } else {
            withCreate = withExistingWindowsServicePlan(azure, model);
        }

        return withCreate
                .withJavaVersion(model.getJdkVersion())
                .withWebContainer(WebContainer.fromString(model.getWebContainer()))
                .create();
    }

    /**
     * API to create Web App on Linux.
     */
    public WebApp createWebAppOnLinux(@NotNull WebAppSettingModel model) throws Exception {
        Azure azure = AuthMethodManager.getInstance().getAzureClient(model.getSubscriptionId());

        WebApp.DefinitionStages.WithDockerContainerImage withCreate;
        if (model.isCreatingAppServicePlan()) {
            withCreate = withCreateNewLinuxServicePlan(azure, model);
        } else {
            withCreate = withExistingLinuxServicePlan(azure, model);
        }

        return withCreate.withBuiltInImage(model.getLinuxRuntime()).create();
    }

    private AppServicePlan.DefinitionStages.WithCreate prepareWithCreate(
        @NotNull Azure azure, @NotNull WebAppSettingModel model) throws Exception {

        final String[] tierSize = model.getPricing().split("_");
        if (tierSize.length != 2) {
            throw new Exception("Cannot get valid price tier");
        }
        final PricingTier pricingTier = new PricingTier(tierSize[0], tierSize[1]);

        final AppServicePlan.DefinitionStages.WithGroup withGroup = azure
            .appServices()
            .appServicePlans()
            .define(model.getAppServicePlanName())
            .withRegion(model.getRegion());

        final AppServicePlan.DefinitionStages.WithPricingTier withPricingTier;
        final String resourceGroup = model.getResourceGroup();
        if (model.isCreatingResGrp()) {
            withPricingTier = withGroup.withNewResourceGroup(resourceGroup);
        } else {
            withPricingTier = withGroup.withExistingResourceGroup(resourceGroup);
        }

        return withPricingTier.withPricingTier(pricingTier).withOperatingSystem(model.getOS());
    }

    private WebApp.DefinitionStages.WithNewAppServicePlan prepareServicePlan(
        @NotNull Azure azure, @NotNull WebAppSettingModel model) {

        final WebApp.DefinitionStages.NewAppServicePlanWithGroup appWithGroup = azure
            .webApps()
            .define(model.getWebAppName())
            .withRegion(model.getRegion());

        final String resourceGroup = model.getResourceGroup();
        if (model.isCreatingResGrp()) {
            return appWithGroup.withNewResourceGroup(resourceGroup);
        }
        return appWithGroup.withExistingResourceGroup(resourceGroup);
    }

    private WebApp.DefinitionStages.WithCreate withCreateNewWindowsServicePlan(
            @NotNull Azure azure, @NotNull WebAppSettingModel model) throws Exception {

        final AppServicePlan.DefinitionStages.WithCreate withCreate = prepareWithCreate(azure, model);
        final WebApp.DefinitionStages.WithNewAppServicePlan withNewAppServicePlan = prepareServicePlan(azure, model);
        return withNewAppServicePlan.withNewWindowsPlan(withCreate);
    }

    private WebApp.DefinitionStages.WithDockerContainerImage withCreateNewLinuxServicePlan(
        @NotNull Azure azure, @NotNull WebAppSettingModel model) throws Exception {

        final AppServicePlan.DefinitionStages.WithCreate withCreate = prepareWithCreate(azure, model);
        final WebApp.DefinitionStages.WithNewAppServicePlan withNewAppServicePlan = prepareServicePlan(azure, model);
        return withNewAppServicePlan.withNewLinuxPlan(withCreate);
    }

    private WebApp.DefinitionStages.WithCreate withExistingWindowsServicePlan(
            @NotNull Azure azure, @NotNull WebAppSettingModel model) {

        AppServicePlan servicePlan = azure.appServices().appServicePlans().getById(model.getAppServicePlanId());
        WebApp.DefinitionStages.ExistingWindowsPlanWithGroup withGroup = azure
            .webApps()
            .define(model.getWebAppName())
            .withExistingWindowsPlan(servicePlan);

        if (model.isCreatingResGrp()) {
            return withGroup.withNewResourceGroup(model.getResourceGroup());
        }
        return withGroup.withExistingResourceGroup(model.getResourceGroup());
    }

    private WebApp.DefinitionStages.WithDockerContainerImage withExistingLinuxServicePlan(
        @NotNull Azure azure, @NotNull WebAppSettingModel model) {

        AppServicePlan servicePlan = azure.appServices().appServicePlans().getById(model.getAppServicePlanId());
        WebApp.DefinitionStages.ExistingLinuxPlanWithGroup withGroup = azure
            .webApps()
            .define(model.getWebAppName())
            .withExistingLinuxPlan(servicePlan);

        if (model.isCreatingResGrp()) {
            return withGroup.withNewResourceGroup(model.getResourceGroup());
        }
        return withGroup.withExistingResourceGroup(model.getResourceGroup());
    }

    public void deployWebApp() {
        // TODO
    }

    public void deleteWebApp(String sid, String appId) throws IOException {
        AuthMethodManager.getInstance().getAzureClient(sid).webApps().deleteById(appId);
        subscriptionIdToWebApps.remove(sid);
    }

    /**
     * API to create Web App on Docker.
     *
     * @param model parameters
     * @return instance of created WebApp
     * @throws IOException IOExceptions
     */
    public WebApp createWebAppWithPrivateRegistryImage(@NotNull WebAppOnLinuxDeployModel model)
            throws IOException {
        PrivateRegistryImageSetting pr = model.getPrivateRegistryImageSetting();
        WebApp app;
        Azure azure = AuthMethodManager.getInstance().getAzureClient(model.getSubscriptionId());
        PricingTier pricingTier = new PricingTier(model.getPricingSkuTier(), model.getPricingSkuSize());

        WebApp.DefinitionStages.Blank webAppDefinition = azure.webApps().define(model.getWebAppName());
        if (model.isCreatingNewAppServicePlan()) {
            // new asp
            AppServicePlan.DefinitionStages.WithCreate asp;
            if (model.isCreatingNewResourceGroup()) {
                // new rg
                asp = azure.appServices().appServicePlans()
                        .define(model.getAppServicePlanName())
                        .withRegion(Region.findByLabelOrName(model.getLocationName()))
                        .withNewResourceGroup(model.getResourceGroupName())
                        .withPricingTier(pricingTier)
                        .withOperatingSystem(OperatingSystem.LINUX);
                app = webAppDefinition
                        .withRegion(Region.findByLabelOrName(model.getLocationName()))
                        .withNewResourceGroup(model.getResourceGroupName())
                        .withNewLinuxPlan(asp)
                        .withPrivateRegistryImage(pr.getImageTagWithServerUrl(), pr.getServerUrl())
                        .withCredentials(pr.getUsername(), pr.getPassword())
                        .withStartUpCommand(pr.getStartupFile()).create();
            } else {
                // old rg
                asp = azure.appServices().appServicePlans()
                        .define(model.getAppServicePlanName())
                        .withRegion(Region.findByLabelOrName(model.getLocationName()))
                        .withExistingResourceGroup(model.getResourceGroupName())
                        .withPricingTier(pricingTier)
                        .withOperatingSystem(OperatingSystem.LINUX);
                app = webAppDefinition
                        .withRegion(Region.findByLabelOrName(model.getLocationName()))
                        .withExistingResourceGroup(model.getResourceGroupName())
                        .withNewLinuxPlan(asp)
                        .withPrivateRegistryImage(pr.getImageTagWithServerUrl(), pr.getServerUrl())
                        .withCredentials(pr.getUsername(), pr.getPassword())
                        .withStartUpCommand(pr.getStartupFile()).create();
            }
        } else {
            // old asp
            AppServicePlan asp = azure.appServices().appServicePlans().getById(model.getAppServicePlanId());
            if (model.isCreatingNewResourceGroup()) {
                // new rg
                app = webAppDefinition
                        .withExistingLinuxPlan(asp)
                        .withNewResourceGroup(model.getResourceGroupName())
                        .withPrivateRegistryImage(pr.getImageTagWithServerUrl(), pr.getServerUrl())
                        .withCredentials(pr.getUsername(), pr.getPassword())
                        .withStartUpCommand(pr.getStartupFile()).create();
            } else {
                // old rg
                app = webAppDefinition
                        .withExistingLinuxPlan(asp)
                        .withExistingResourceGroup(model.getResourceGroupName())
                        .withPrivateRegistryImage(pr.getImageTagWithServerUrl(), pr.getServerUrl())
                        .withCredentials(pr.getUsername(), pr.getPassword())
                        .withStartUpCommand(pr.getStartupFile()).create();
            }
        }
        return app;
        // TODO: update cache
    }

    /**
     * Update container settings for existing Web App on Linux.
     *
     * @param sid          Subscription id
     * @param webAppId     id of Web App on Linux instance
     * @param imageSetting new container settings
     * @return instance of the updated Web App on Linux
     */
    public WebApp updateWebAppOnDocker(String sid, String webAppId, ImageSetting imageSetting) throws Exception {
        WebApp app = getWebAppById(sid, webAppId);
        clearTags(app);
        if (imageSetting instanceof PrivateRegistryImageSetting) {
            PrivateRegistryImageSetting pr = (PrivateRegistryImageSetting) imageSetting;
            app.update().withPrivateRegistryImage(pr.getImageTagWithServerUrl(), pr.getServerUrl())
                    .withCredentials(pr.getUsername(), pr.getPassword())
                    .withStartUpCommand(pr.getStartupFile()).apply();
        } else {
            // TODO: other types of ImageSetting, e.g. Docker Hub
        }
        // status-free restart.
        stopWebApp(sid, webAppId);
        startWebApp(sid, webAppId);
        return app;
    }

    /**
     * Update app settings of webapp.
     *
     * @param sid      subscription id
     * @param webAppId webapp id
     * @param toUpdate entries to add/modify
     * @param toRemove entries to remove
     * @throws Exception exception
     */
    public void updateWebAppSettings(String sid, String webAppId, Map<String, String> toUpdate, Set<String> toRemove)
            throws Exception {
        WebApp app = getWebAppById(sid, webAppId);
        clearTags(app);
        com.microsoft.azure.management.appservice.WebAppBase.Update<WebApp> update = app.update()
                .withAppSettings(toUpdate);
        for (String key : toRemove) {
            update = update.withoutAppSetting(key);
        }
        update.apply();
    }

    /**
     * Update app settings of deployment slot.
     */
    public void updateDeploymentSlotAppSettings(final String subsciptionId, final String webAppId,
                                                final String slotName, final Map<String, String> toUpdate,
                                                final Set<String> toRemove) throws Exception {
        final DeploymentSlot slot = getWebAppById(subsciptionId, webAppId).deploymentSlots().getByName(slotName);
        clearTags(slot);
        com.microsoft.azure.management.appservice.WebAppBase.Update<DeploymentSlot> update = slot.update()
            .withAppSettings(toUpdate);
        for (String key : toRemove) {
            update = update.withoutAppSetting(key);
        }
        update.apply();
    }

    public void deleteWebAppOnLinux(String sid, String appid) throws IOException {
        deleteWebApp(sid, appid);
    }

    public void restartWebApp(String sid, String appid) throws IOException {
        AuthMethodManager.getInstance().getAzureClient(sid).webApps().getById(appid).restart();
    }

    public void startWebApp(String sid, String appid) throws IOException {
        AuthMethodManager.getInstance().getAzureClient(sid).webApps().getById(appid).start();
    }

    public void stopWebApp(String sid, String appid) throws IOException {
        AuthMethodManager.getInstance().getAzureClient(sid).webApps().getById(appid).stop();
    }

    public void startDeploymentSlot(final String subscriptionId, final String appId,
                                    final String slotName) throws IOException {
        final WebApp app = AuthMethodManager.getInstance().getAzureClient(subscriptionId).webApps().getById(appId);
        app.deploymentSlots().getByName(slotName).start();
    }

    public void stopDeploymentSlot(final String subscriptionId, final String appId,
                                   final String slotName) throws IOException {
        final WebApp app = AuthMethodManager.getInstance().getAzureClient(subscriptionId).webApps().getById(appId);
        app.deploymentSlots().getByName(slotName).stop();
    }

    public void restartDeploymentSlot(final String subscriptionId, final String appId,
                                      final String slotName) throws IOException {
        final WebApp app = AuthMethodManager.getInstance().getAzureClient(subscriptionId).webApps().getById(appId);
        app.deploymentSlots().getByName(slotName).restart();
    }

    public void swapSlotWithProduction(final String subscriptionId, final String appId,
                                       final String slotName) throws IOException {
        final WebApp app = AuthMethodManager.getInstance().getAzureClient(subscriptionId).webApps().getById(appId);
        final DeploymentSlot slot = app.deploymentSlots().getByName(slotName);
        slot.swap("production");
    }

    public void deleteDeploymentSlotNode(final String subscriptionId, final String appId,
                                         final String slotName) throws IOException {
        final WebApp app = AuthMethodManager.getInstance().getAzureClient(subscriptionId).webApps().getById(appId);
        app.deploymentSlots().deleteByName(slotName);
    }

    /**
     * Get all the deployment slots of a web app by the subscription id and web app id.
     */
    public List<DeploymentSlot> getDeploymentSlots(final String subscriptionId, final String appId) throws IOException {
        final List<DeploymentSlot> deploymentSlots = new ArrayList<>();
        final WebApp webApp = AuthMethodManager.getInstance().getAzureClient(subscriptionId).webApps().getById(appId);
        deploymentSlots.addAll(webApp.deploymentSlots().list());
        return deploymentSlots;
    }

    /**
     * List app service plan by subscription id and resource group name.
     */
    public List<AppServicePlan> listAppServicePlanBySubscriptionIdAndResourceGroupName(String sid, String group) {
        List<AppServicePlan> appServicePlans = new ArrayList<>();
        try {
            Azure azure = AuthMethodManager.getInstance().getAzureClient(sid);
            appServicePlans.addAll(azure.appServices().appServicePlans().listByResourceGroup(group));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return appServicePlans;
    }


    /**
     * List app service plan by subscription id.
     */
    public List<AppServicePlan> listAppServicePlanBySubscriptionId(String sid) throws IOException {
        return AuthMethodManager.getInstance().getAzureClient(sid).appServices().appServicePlans().list();
    }

    /**
     * List Web Apps by Subscription ID.
     */
    @Deprecated
    public List<ResourceEx<WebApp>> listWebAppsOnWindowsBySubscriptionId(final String sid, final boolean force) {
        return this.listWebAppsOnWindows(sid, force);
    }

    /**
     * List all the Web Apps on Windows in selected subscriptions.
     */
    public List<ResourceEx<WebApp>> listAllWebAppsOnWindows(final boolean force) {
        final List<ResourceEx<WebApp>> webApps = new ArrayList<>();
        for (final Subscription sub : AzureMvpModel.getInstance().getSelectedSubscriptions()) {
            final String sid = sub.subscriptionId();
            webApps.addAll(listWebAppsOnWindows(sid, force));
        }
        return webApps;
    }

    /**
     * List Web App on Linux by Subscription ID.
     *
     * @param sid   subscription Id
     * @param force flag indicating whether force to fetch most updated data from server
     * @return list of Web App on Linux
     */
    @Deprecated
    public List<ResourceEx<WebApp>> listWebAppsOnLinuxBySubscriptionId(final String sid, final boolean force) {
        return this.listWebAppsOnLinux(sid, force);
    }

    /**
     * List all the Web Apps in selected subscriptions.
     *
     * @param force flag indicating whether force to fetch most updated data from server
     * @return list of Web App
     */
    public List<ResourceEx<WebApp>> listAllWebApps(final boolean force) {
        final List<ResourceEx<WebApp>> webApps = new ArrayList<>();
        for (final Subscription sub : AzureMvpModel.getInstance().getSelectedSubscriptions()) {
            final String sid = sub.subscriptionId();
            webApps.addAll(listWebApps(sid, force));
        }
        return webApps;
    }

    /**
     * List web apps on linux by subscription id.
     */
    public List<ResourceEx<WebApp>> listWebAppsOnLinux(@NotNull final String subscriptionId, final boolean force) {
        return listWebApps(subscriptionId, force)
            .stream()
            .filter(resourceEx -> OperatingSystem.LINUX.equals(resourceEx.getResource().operatingSystem()))
            .collect(Collectors.toList());
    }

    /**
     * List web apps on windows by subscription id.
     */
    public List<ResourceEx<WebApp>> listWebAppsOnWindows(@NotNull final String subscriptionId, final boolean force) {
        return listWebApps(subscriptionId, force)
            .stream()
            .filter(resourceEx -> OperatingSystem.WINDOWS.equals((resourceEx.getResource().operatingSystem())))
            .collect(Collectors.toList());
    }

    /**
     * List all web apps by subscription id.
     */
    @NotNull
    public List<ResourceEx<WebApp>> listWebApps(final String subscriptionId, final boolean force) {
        if (!force && subscriptionIdToWebApps.get(subscriptionId) != null) {
            return subscriptionIdToWebApps.get(subscriptionId);
        }

        List<ResourceEx<WebApp>> webApps = new ArrayList<>();
        try {
            final Azure azure = AuthMethodManager.getInstance().getAzureClient(subscriptionId);
            webApps = azure.webApps().list()
                .stream()
                .map(app -> new ResourceEx<WebApp>(app, subscriptionId))
                .collect(Collectors.toList());
            subscriptionIdToWebApps.put(subscriptionId, webApps);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return webApps;
    }

    /**
     * List available Web Containers.
     */
    public List<WebAppUtils.WebContainerMod> listWebContainers() {
        List<WebAppUtils.WebContainerMod> webContainers = new ArrayList<>();
        Collections.addAll(webContainers, WebAppUtils.WebContainerMod.values());
        return webContainers;
    }

    /**
     * List available Third Party JDKs.
     */
    public List<JdkModel> listJdks() {
        List<JdkModel> jdkModels = new ArrayList<>();
        Collections.addAll(jdkModels, JdkModel.values());
        return jdkModels;
    }

    /**
     * List all available Java linux RuntimeStacks.
     * todo: For those unchanged list, like jdk versions, web containers,
     * linux runtimes, do we really need to get the values from Mvp model every time?
     */
    public List<RuntimeStack> getLinuxRuntimes() {
        return WebAppUtils.getAllJavaLinuxRuntimeStacks();
    }

    /**
     * List all the Web Apps on Linux in selected subscriptions.
     * @param force flag indicating whether force to fetch most updated data from server
     * @return list of Web App on Linux
     */
    public List<ResourceEx<WebApp>> listAllWebAppsOnLinux(final boolean force) {
        final List<ResourceEx<WebApp>> webApps = new ArrayList<>();
        for (final Subscription sub : AzureMvpModel.getInstance().getSelectedSubscriptions()) {
            final String sid = sub.subscriptionId();
            webApps.addAll(listWebAppsOnLinux(sid, force));
        }
        return webApps;
    }

    /**
     * Download publish profile of web app.
     *
     * @param sid      subscription id
     * @param webAppId webapp id
     * @param filePath file path to save publish profile
     * @return status indicating whether it is successful or not
     * @throws Exception exception
     */
    public boolean getPublishingProfileXmlWithSecrets(String sid, String webAppId, String filePath) throws Exception {
        WebApp app = getWebAppById(sid, webAppId);
        File file = new File(Paths.get(filePath, app.name() + "_" + System.currentTimeMillis() + ".PublishSettings")
                .toString());
        file.createNewFile();
        try (InputStream inputStream = app.manager().inner().webApps()
                .listPublishingProfileXmlWithSecrets(app.resourceGroupName(), app.name(),
                        new CsmPublishingProfileOptions().withFormat(PublishingProfileFormat.FTP));
             OutputStream outputStream = new FileOutputStream(file);
        ) {
            IOUtils.copy(inputStream, outputStream);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Download publish profile of deployment slot.
     */
    public boolean getSlotPublishingProfileXmlWithSecrets(final String sid, final String webAppId, final String slotName,
                                                          final String filePath) throws Exception {
        final WebApp app = getWebAppById(sid, webAppId);
        final DeploymentSlot slot = app.deploymentSlots().getByName(slotName);
        final File file = new File(Paths.get(filePath, slotName + "_" + System.currentTimeMillis() + ".PublishSettings")
            .toString());
        file.createNewFile();
        try (final InputStream inputStream = slot.manager().inner().webApps()
            .listPublishingProfileXmlWithSecretsSlot(slot.resourceGroupName(), app.name(), slotName,
                    new CsmPublishingProfileOptions().withFormat(PublishingProfileFormat.FTP));
             OutputStream outputStream = new FileOutputStream(file);
        ) {
            IOUtils.copy(inputStream, outputStream);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void clearWebAppsCache() {
        subscriptionIdToWebApps.clear();
    }

    /**
     * Work Around:
     * When a web app is created from Azure Portal, there are hidden tags associated with the app.
     * It will be messed up when calling "update" API.
     * An issue is logged at https://github.com/Azure/azure-libraries-for-java/issues/508 .
     * Remove all tags here to make it work.
     */
    private void clearTags(@NotNull final WebAppBase app) {
        app.inner().withTags(null);
    }

    private static final class SingletonHolder {
        private static final AzureWebAppMvpModel INSTANCE = new AzureWebAppMvpModel();
    }
}
