# Sample for using user managed identity in AKS

### Prerequisites

* [OracleJDK](https://www.oracle.com/java/technologies/javase-downloads.html) or [OpenJDK](https://openjdk.java.net/)
* [Maven](https://maven.apache.org/)
* [kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl/)

### Run samples

See [DefaultAzureCredential](https://github.com/Azure/azure-sdk-for-java/tree/master/sdk/identity/azure-identity#defaultazurecredential) and prepare the authentication works best for you.

For more details on authentication, please refer to [AUTH.md](https://github.com/Azure/azure-sdk-for-java/blob/master/sdk/resourcemanager/docs/AUTH.md).

Program running inside [pod](test-pod.template.yml#L14): https://github.com/Azure-Samples/compute-java-manage-vm-from-vm-with-msi-credentials

```bash
mvn clean compile exec:java # create identity and AKS, also write test-pod.yml and test.kubeconfig
kubectl apply -f test-pod.yml --kubeconfig test.kubeconfig
kubectl logs -f test --kubeconfig test.kubeconfig
``` 

### Already has AKS with identity

Replace `%s %s %s` in `test-pod.template.yml` with `subscription id`, `resource group name` and `identity client id`. Make sure the identity can create a virtual machine in the resource group.

Add the following property in the AKS properties.
```json
{
    "podIdentityProfile": {
        "enabled": true,
        "userAssignedIdentities": [{
            "name": "default",
            "namespace": "default",
            "identity": {
                "resourceId": "<identity id>",
                "clientId": "<identity client id>",
                "objectId": "<identity pricinpal id>"
            }
        }]
    }
}
```

Then run

```bash
kubectl apply -f test-pod.template.yml
kubectl logs -f test
```