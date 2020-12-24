# Sample for using user managed identity in AKS

### Prerequisites

* [Dotnet](https://dotnet.microsoft.com/)
* [kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl/)

### Run samples

Set the environment variable AZURE_AUTH_LOCATION with the full path for an auth file. See [how to create an auth file](https://github.com/Azure/azure-libraries-for-net/blob/master/AUTH.md).

Program running inside [pod](test-pod.template.yml#L14): https://github.com/tanyi-test/azure-libraries-for-net-staging/tree/msi-net5.0

```bash
dotnet run test.csproj --framework net5.0 # create identity and AKS, also write test-pod.yml and test.kubeconfig
kubectl apply -f test-pod.yml --kubeconfig test.kubeconfig
kubectl logs -f test --kubeconfig test.kubeconfig
``` 

### Already has AKS with identity

Replace `{0} {1} {2}` in `test-pod.template.yml` with `subscription id`, `resource group name` and `identity client id`. Make sure the identity can read the resource group.

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