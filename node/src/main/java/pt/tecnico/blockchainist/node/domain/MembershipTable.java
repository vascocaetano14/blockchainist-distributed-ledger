package pt.tecnico.blockchainist.node.domain;

import java.util.Map;
import java.util.Set;

public class MembershipTable {
    
    private static final Map<String, String> USER_TO_ORGANIZATION = Map.ofEntries(
        Map.entry("BC", "OrgA"),
        Map.entry("Alice", "OrgA"),
        Map.entry("Bob", "OrgA"),
        Map.entry("Charlie", "OrgA"),
        Map.entry("David", "OrgB"),
        Map.entry("Emma", "OrgB"),
        Map.entry("Fred", "OrgB"),
        Map.entry("Ginger", "OrgC"),
        Map.entry("Henry", "OrgC"),
        Map.entry("Iris", "OrgC")
    );

    //diz a organização a que um user pertence
    public static String getOrganization(String userId) {
        return USER_TO_ORGANIZATION.get(userId);
    }

    //verifica se um user pertence a uma organização
    public static boolean belongsTo(String userId, String organizationName) {
        String organization = getOrganization(userId);
        return organizationName.equals(organization);
    }
    
    //forma de obter globalmente todos os utilizadores estáticos para precarregar as respetivas chaves públicas
    public static Set<String> getAllUserIds() {
        return USER_TO_ORGANIZATION.keySet();
    }
}
