package pl.kasyno.show;

/** Pokaz sterowany tickami serwera (napad, pojedynek, jackpot): ShowService popycha go co tick i sprząta po nim. */
public interface Scene {
    /** Jeden tick pokazu. Zwraca true, gdy pokaz się skończył. */
    boolean tick();

    /** Przerywa pokaz (wyłączenie serwera, komenda): usuwa wszystkie postacie i sprząta; nie woła zakończenia. */
    void abort();

    boolean done();
}
