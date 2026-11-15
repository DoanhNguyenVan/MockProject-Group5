
package com.boxcinema.service.impl;

import com.boxcinema.entity.Genre;
import com.boxcinema.repository.GenreRepository;
import com.boxcinema.service.GenreManagementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class GenreManagementServiceImpl
        implements GenreManagementService {

    private final GenreRepository genreRepository;

    public GenreManagementServiceImpl(
            GenreRepository genreRepository) {
        this.genreRepository = genreRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Genre> getAllGenres() {
        return genreRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(
                        Genre::getGenreName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Genre getGenreById(Long genreId) {

        if (genreId == null) {
            throw new IllegalArgumentException(
                    "Genre ID is required."
            );
        }

        return genreRepository.findById(genreId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Genre not found."
                        )
                );
    }

    @Override
    public Genre createGenre(
            String genreName,
            String description) {

        String cleanName = validateGenreName(genreName);
        String cleanDescription = normalizeDescription(description);

        boolean duplicated = genreRepository.findAll()
                .stream()
                .anyMatch(genre ->
                        genre.getGenreName()
                                .equalsIgnoreCase(cleanName)
                );

        if (duplicated) {
            throw new IllegalArgumentException(
                    "This genre already exists."
            );
        }

        Genre genre = new Genre(
                cleanName,
                cleanDescription
        );

        return genreRepository.save(genre);
    }

    @Override
    public Genre updateGenre(
            Long genreId,
            String genreName,
            String description) {

        Genre genre = getGenreById(genreId);

        String cleanName = validateGenreName(genreName);
        String cleanDescription = normalizeDescription(description);

        boolean duplicated = genreRepository.findAll()
                .stream()
                .anyMatch(existing ->
                        !existing.getGenreId().equals(genreId)
                                && existing.getGenreName()
                                .equalsIgnoreCase(cleanName)
                );

        if (duplicated) {
            throw new IllegalArgumentException(
                    "This genre already exists."
            );
        }

        genre.setGenreName(cleanName);
        genre.setDescription(cleanDescription);

        return genreRepository.save(genre);
    }

    @Override
    public void deleteGenre(Long genreId) {

        Genre genre = getGenreById(genreId);

        if (genre.getMovies() != null
                && !genre.getMovies().isEmpty()) {

            throw new IllegalArgumentException(
                    "Cannot delete this genre because it is assigned to one or more movies."
            );
        }

        genreRepository.delete(genre);
    }

    private String validateGenreName(String genreName) {

        if (genreName == null || genreName.isBlank()) {
            throw new IllegalArgumentException(
                    "Genre name is required."
            );
        }

        String cleanName = genreName.trim();

        if (cleanName.length() > 50) {
            throw new IllegalArgumentException(
                    "Genre name cannot exceed 50 characters."
            );
        }

        return cleanName;
    }

    private String normalizeDescription(String description) {

        if (description == null || description.isBlank()) {
            return null;
        }

        return description.trim();
    }
}
