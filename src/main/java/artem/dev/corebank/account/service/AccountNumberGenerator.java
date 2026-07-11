package artem.dev.corebank.account.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {

    private static final int ACCOUNT_NUMBER_LENGTH = 20;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder accountNumber = new StringBuilder(ACCOUNT_NUMBER_LENGTH);
        accountNumber.append(secureRandom.nextInt(9) + 1);
        for (int index = 1; index < ACCOUNT_NUMBER_LENGTH; index++) {
            accountNumber.append(secureRandom.nextInt(10));
        }
        return accountNumber.toString();
    }
}
