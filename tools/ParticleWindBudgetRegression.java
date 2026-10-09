package com.axes.pmweather_aeronautics;

/** Dependency-free manual regression for the per-tick particle application/query limits. */
public final class ParticleWindBudgetRegression {
    private ParticleWindBudgetRegression() {}

    public static void main(String[] args) {
        ParticleWindBudget budget = new ParticleWindBudget();
        budget.beginClientTick();
        int queries = 0;
        for (int i = 0; i < ParticleWindBudget.MAX_REFRESH_QUERIES * 4; i++) {
            if (budget.reserveRefreshQuery()) queries++;
        }
        require(queries == ParticleWindBudget.MAX_REFRESH_QUERIES, "query budget must stop at eight");

        Object[] particles = new Object[10_000];
        for (int i = 0; i < particles.length; i++) particles[i] = new Object();
        int applications = 0;
        for (Object particle : particles) if (budget.reserveApplication(particle)) applications++;
        require(applications <= ParticleWindBudget.MAX_APPLICATIONS, "application budget exceeded its hard cap");
        require(applications > ParticleWindBudget.MAX_APPLICATIONS / 2, "stable buckets starved too many particles");

        budget.beginClientTick();
        require(budget.reserveRefreshQuery(), "query budget must reset for a new tick");
        require(budget.reserveApplication(particles[0]), "application budget must reset for a new tick");
        require(budget.applications() == 1, "new tick must start with one accepted application");
        System.out.println("Particle wind budget regression passed: query cap, application cap, and per-tick reset.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
