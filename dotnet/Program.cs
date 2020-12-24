
using Microsoft.Azure.Management.ContainerService.Fluent;
using Microsoft.Azure.Management.ContainerService.Fluent.Models;
using Microsoft.Azure.Management.Graph.RBAC.Fluent;
using Microsoft.Azure.Management.Msi.Fluent;
using Microsoft.Azure.Management.ResourceManager.Fluent.Authentication;
using Microsoft.Azure.Management.ResourceManager.Fluent.Core;
using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;

namespace test
{
    class Program
    {
        static void Main(string[] args)
        {
            main(new System.Threading.CancellationToken()).Wait();
        }

        static string randomString(string prefix, int length)
        {
            return prefix + Guid.NewGuid().ToString().Replace("-", "").Substring(0, length - prefix.Length);
        }

        static async Task main(System.Threading.CancellationToken cancellationToken)
        {
            var credentials = new AzureCredentialsFactory()
                .FromFile(Environment.GetEnvironmentVariable("AZURE_AUTH_LOCATION"));

            var containerServiceManager = ContainerServiceManager
                .Authenticate(credentials, credentials.DefaultSubscriptionId);
            var msiManager = MsiManager
                .Authenticate(credentials, credentials.DefaultSubscriptionId);

            var region = Region.USEast;
            var rgName = randomString("rg", 12);
            var identityName = randomString("id", 12);
            var aksName = randomString("aks", 12);
            var dnsPrefix = randomString("dns", 12);

            var resourceGroupForMsi = randomString("rg", 12);

            Console.WriteLine("Creating resource group for msi ...");

            var msiResourceGroup = await containerServiceManager.ResourceManager.ResourceGroups.Define(resourceGroupForMsi)
                .WithRegion(region)
                .CreateAsync(cancellationToken);

            Console.WriteLine("Creating user assigned identity ...");

            var identity = await msiManager.Identities.Define(identityName)
                .WithRegion(region)
                .WithNewResourceGroup(rgName)
                .WithAccessTo(msiResourceGroup, BuiltInRole.Contributor)
                .WithAccessToCurrentResourceGroup(BuiltInRole.Contributor)
                .CreateAsync(cancellationToken);

            Console.WriteLine("Creating managed cluster ...");

            var managedCluster = new ManagedClusterInner()
            {
                Location = region.ToString(),
                AgentPoolProfiles = new List<ManagedClusterAgentPoolProfile>()
                {
                    new ManagedClusterAgentPoolProfile()
                    {
                        Name = "nodepool1",
                        VmSize = ContainerServiceVMSizeTypes.StandardB2s,
                        OsType = OSType.Linux,
                        Mode = AgentPoolMode.System,
                        Count = 1
                    }
                },
                DnsPrefix = dnsPrefix,
                Identity = new ManagedClusterIdentity()
                {
                    Type = ResourceIdentityType.UserAssigned,
                    UserAssignedIdentities = new Dictionary<string, ManagedClusterIdentityUserAssignedIdentitiesValue>()
                    {
                        {identity.Id,  new ManagedClusterIdentityUserAssignedIdentitiesValue()}
                    }
                },
                NetworkProfile = new ContainerServiceNetworkProfile()
                {
                    NetworkPlugin = NetworkPlugin.Azure
                },
                PodIdentityProfile = new ManagedClusterPodIdentityProfile()
                {
                    Enabled = true
                }
            };

            managedCluster = await containerServiceManager.KubernetesClusters.Inner.CreateOrUpdateAsync(rgName, aksName, managedCluster, cancellationToken);

            Console.WriteLine("Updating managed cluster ...");

            managedCluster.PodIdentityProfile.UserAssignedIdentities = new List<ManagedClusterPodIdentity>()
            {
                new ManagedClusterPodIdentity()
                {
                    Name = "default",
                    NamespaceProperty = "default",
                    Identity = new UserAssignedIdentity()
                    {
                        ResourceId = identity.Id,
                        ClientId = identity.ClientId,
                        ObjectId = identity.PrincipalId
                    }
                }
            };

            managedCluster = await containerServiceManager.KubernetesClusters.Inner.CreateOrUpdateAsync(rgName, aksName, managedCluster, cancellationToken);

            Console.WriteLine("Downloading managed cluster credentials ...");

            var test_pod_template = await download("https://github.com/tanyi-test/azure-k8s-pod-with-identity/raw/master/dotnet/test-pod.template.yml");
            var test_pod = string.Format(test_pod_template, credentials.DefaultSubscriptionId, resourceGroupForMsi, identity.ClientId);
            File.WriteAllText("test-pod.yml", test_pod);

            var aksCredentials = await containerServiceManager.KubernetesClusters.Inner.ListClusterAdminCredentialsAsync(rgName, aksName, cancellationToken);
            if (aksCredentials.Kubeconfigs.Count > 0)
            {
                File.WriteAllBytes("test.kubeconfig", aksCredentials.Kubeconfigs[0].Value);
            }
            else
            {
                throw new Exception("Cannot get admin config from kubernetes clusters");
            }
        }

        static async Task<string> download(string url)
        {
            var httpClient = new HttpClient();
            return await (await httpClient.GetAsync(url)).Content.ReadAsStringAsync();
        }
    }
}