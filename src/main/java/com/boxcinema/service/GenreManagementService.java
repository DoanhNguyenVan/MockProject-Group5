
package com.boxcinema.service;

import com.boxcinema.entity.Genre;

import java.util.List;

public interface GenreManagementService {

    List<Genre> getAllGenres();

    Genre getGenreById(Long genreId);

    Genre createGenre(String genreName, String description);

    Genre updateGenre(Long genreId, String genreName, String description);

    void deleteGenre(Long genreId);
}
