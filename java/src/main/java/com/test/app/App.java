package com.test.app;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.Region;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.BuiltInRole;
import com.azure.resourcemanager.containerservice.fluent.models.ManagedClusterInner;
import com.azure.resourcemanager.containerservice.models.AgentPoolMode;
import com.azure.resourcemanager.containerservice.models.ContainerServiceNetworkProfile;
import com.azure.resourcemanager.containerservice.models.ContainerServiceVMSizeTypes;
import com.azure.resourcemanager.containerservice.models.CredentialResult;
import com.azure.resourcemanager.containerservice.models.ManagedClusterAgentPoolProfile;
import com.azure.resourcemanager.containerservice.models.ManagedClusterIdentity;
import com.azure.resourcemanager.containerservice.models.ManagedClusterIdentityUserAssignedIdentities;
import com.azure.resourcemanager.containerservice.models.ManagedClusterPodIdentity;
import com.azure.resourcemanager.containerservice.models.ManagedClusterPodIdentityProfile;
import com.azure.resourcemanager.containerservice.models.NetworkPlugin;
import com.azure.resourcemanager.containerservice.models.OSType;
import com.azure.resourcemanager.containerservice.models.ResourceIdentityType;
import com.azure.resourcemanager.containerservice.models.UserAssignedIdentity;
import com.azure.resourcemanager.msi.models.Identity;
import com.azure.resourcemanager.resources.models.ResourceGroup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

public class App {
    public static String random(String prefix, int length) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, length - prefix.length());
    }

    public static void main(String[] args) throws IOException {
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        AzureProfile profile = new AzureProfile(AzureEnvironment.AZURE);

        AzureResourceManager azure = AzureResourceManager.authenticate(credential, profile).withDefaultSubscription();

        String rgName = random("rg", 12);
        String identityName = random("id", 12);
        String aksName = random("aks", 12);
        String dnsPrefix = random("dns", 12);
        Region region = Region.US_EAST;

        String resourceGroupForMsi = random("rg", 12);

        System.out.printf("Creating resource group %s ...%n", resourceGroupForMsi);

        ResourceGroup msiResourceGroup = azure.resourceGroups().define(resourceGroupForMsi)
                .withRegion(region)
                .create();

        System.out.printf("Creating resource group %s ...%n", rgName);
        System.out.printf("Creating user assigned identity %s ...%n", identityName);

        Identity identity = azure.identities().define(identityName)
                .withRegion(region)
                .withNewResourceGroup(rgName)
                .withAccessTo(msiResourceGroup, BuiltInRole.CONTRIBUTOR)
                .withAccessToCurrentResourceGroup(BuiltInRole.CONTRIBUTOR)
                .create();

        System.out.printf("Creating managed cluster %s ...%n", aksName);

        ManagedClusterInner managedCluster = new ManagedClusterInner()
                .withAgentPoolProfiles(Collections.singletonList(
                        new ManagedClusterAgentPoolProfile()
                            .withName("nodepool1")
                            .withVmSize(ContainerServiceVMSizeTypes.STANDARD_B2S)
                            .withOsType(OSType.LINUX)
                            .withMode(AgentPoolMode.SYSTEM)
                            .withCount(1)
                ))
                .withDnsPrefix(dnsPrefix)
                .withIdentity(
                        new ManagedClusterIdentity()
                            .withType(ResourceIdentityType.USER_ASSIGNED)
                            .withUserAssignedIdentities(new HashMap<>(){{
                                put(identity.id(), new ManagedClusterIdentityUserAssignedIdentities());
                            }})
                )
                .withNetworkProfile(
                        new ContainerServiceNetworkProfile()
                            .withNetworkPlugin(NetworkPlugin.AZURE)
                )
                .withPodIdentityProfile(
                        new ManagedClusterPodIdentityProfile()
                            .withEnabled(true)
                );
        managedCluster.withLocation(region.toString());

        managedCluster = azure.kubernetesClusters().manager().serviceClient().getManagedClusters().createOrUpdate(rgName, aksName, managedCluster);

        System.out.println("Updating managed cluster ...");

        managedCluster.withPodIdentityProfile(
                managedCluster.podIdentityProfile()
                    .withUserAssignedIdentities(Collections.singletonList(
                        new ManagedClusterPodIdentity()
                                .withName("default")
                                .withNamespace("default")
                                .withIdentity(new UserAssignedIdentity().withResourceId(identity.id()).withClientId(identity.clientId()).withObjectId(identity.principalId()))
                    ))
        );
        managedCluster = azure.kubernetesClusters().manager().serviceClient().getManagedClusters().createOrUpdate(rgName, aksName, managedCluster);

        System.out.println("Downloading managed cluster credentials ...");

        String test_pod_template = new String(Files.readAllBytes(new File("test-pod.template.yml").toPath()), StandardCharsets.UTF_8);
        String test_pod = String.format(test_pod_template, azure.subscriptionId(), resourceGroupForMsi, identity.clientId());
        Files.write(new File("test-pod.yml").toPath(), test_pod.getBytes(StandardCharsets.UTF_8));

        Optional<CredentialResult> credentialResult = azure.kubernetesClusters().listAdminKubeConfigContent(rgName, aksName).stream().findFirst();

        if (credentialResult.isPresent()) {
            Files.write(new File("test.kubeconfig").toPath(), credentialResult.get().value());
        } else {
            throw new RuntimeException("Cannot get admin config from kubernetes clusters");
        }
    }
}
