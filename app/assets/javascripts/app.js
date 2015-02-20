window.name = "NG_DEFER_BOOTSTRAP!";

require([
  "angular",
  "diff2html",
  "diff",
  "bootstrap",
  "angular-highlightjs-min",
  "highlightjs-lang-java-min",
  "css!bootstrap-css",
  "css!diff2html-css",
  "css!highlightjs-style-github-min",
  "css!app-css"
], function(angular, Diff2Html, diff) {

  angular.module("myApp", ["hljs"])
    .controller("RemoteController", function($scope, $http, $sce) {

      $http.defaults.headers.common["X-ENCRYPTED-OWNER-ID"] = window.encOwnerId;

      $scope.remotes = [];
      $scope.selectedRemotes = [];
      $scope.viewRemoteData = {};
      $scope.fromRemoteData = {};
      $scope.toRemoteData = {};
      $scope.diffData = {};
      $scope.pos = {isSelectAll: false};
      $scope.isGithubReady = false;
      $scope.githubRepos = [];


      $scope.mergeAll = function() {
        var all = true;
        angular.forEach($scope.diffData.apexclasses, function(value) {
          if (!value.selectedForMerge) {
            all = false;
          }
        });
        return all;
      };

      $scope.hasData = function(data) {
        if (angular.isObject(data)) {
          return Object.keys(data).length;
        }
        else {
          return false;
        }
      };

      $scope.updateSelection = function() {
        angular.forEach($scope.diffData.apexclasses, function(value) {
          value.selectedForMerge = $scope.pos.isSelectAll;
        });
      };

      $scope.$watchCollection(function() {
        return [$scope.fromRemoteData, $scope.toRemoteData];
      }, function() {
        if (($scope.fromRemoteData.apexclasses === undefined) || ($scope.toRemoteData.apexclasses === undefined)) {
          return;
        }

        var apexclasses = {};

        function defaultApexClass() {
          return {
            from: {
              Body: null
            },
            to: {
              Body: null
            }
          };
        }

        $scope.fromRemoteData.apexclasses.forEach(function(item) {
          if (apexclasses[item.Name] === undefined) {
            apexclasses[item.Name] = defaultApexClass();
          }
          apexclasses[item.Name].from = item;
        });

        $scope.toRemoteData.apexclasses.forEach(function(item) {
          if (apexclasses[item.Name] === undefined) {
            apexclasses[item.Name] = defaultApexClass();
          }
          apexclasses[item.Name].to = item;
        });

        // remove matching classes and set properties that indicate create, update, or delete
        angular.forEach(apexclasses, function(apexClass, apexClassName) {
          if ((apexClass.to.Body === null) && (apexClass.from.Body !== null)) {
            apexClass.create = true;
          }
          else if ((apexClass.to.Body !== null) && (apexClass.from.Body !== null) && (apexClass.to.Body != apexClass.from.Body)) {
            apexClass.update = true;
          }
          else if ((apexClass.to.Body !== null) && (apexClass.from.Body !== null) && (apexClass.to.Body == apexClass.from.Body)) {
            delete apexclasses[apexClassName];
          }
          else if ((apexClass.to.Body !== null) && (apexClass.from.Body === null)) {
            apexClass.delete = true;
          }
        });

        // calculate diffs
        for (var apexClassKey in apexclasses) {
          if (apexclasses.hasOwnProperty(apexClassKey)) {
            var apexClass = apexclasses[apexClassKey];

            var patchText = diff.createPatch(apexClassKey, apexClass.to.Body, apexClass.from.Body);
            patchText = "diff --git a/" + apexClassKey + " b/" + apexClassKey + "\n" + patchText;

            var diffHtml = Diff2Html.getPrettySideBySideHtmlFromDiff(patchText);

            var e = angular.element(diffHtml);

            apexclasses[apexClassKey].diff = $sce.trustAsHtml(e.html());
          }
        }

        $scope.diffData.apexclasses = apexclasses;
      });

      function fetchOrgs() {
        $http
          .get("/orgs")
          .success(function(data, status, headers, config) {
            angular.forEach(data, function(org) {
              org.org = true;
              $scope.remotes.push(org);
            });
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      }

      // fetch the user's repos
      function fetchRepos() {
        $http
          .get("/repos")
          .success(function(data, status, headers, config) {
            angular.forEach(data, function(repo) {
              repo.repo = true;
              $scope.remotes.push(repo);
            });
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      }

      function fetchRemoteData(remote, callback) {
        if (remote.org) {
          $scope.fetchOrgData(remote.id, callback);
        }
        else if (remote.repo) {
          $scope.fetchRepoData(remote.id, callback);
        }
      }

      $scope.fetchOrgData = function(id, callback) {
        $http
          .get("/orgs/" + id + "/metadata")
          .success(function(data, status, headers, config) {
            data.org = true;
            callback(data);
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      $scope.fetchRepoData = function(id, callback) {
        $http
          .get("/repos/" + id + "/metadata")
          .success(function(data, status, headers, config) {
            data.repo = true;
            callback(data);
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      $scope.selectRemote = function(remote) {
        var i = $scope.selectedRemotes.indexOf(remote);

        if (i >= 0) {
          // unselect
          $scope.selectedRemotes.splice(i, 1);
        }
        else if ($scope.selectedRemotes.length < 2) {
          // add selection
          $scope.selectedRemotes.push(remote);
        }
        else if ($scope.selectedRemotes.length == 2) {
          // replace selection
          $scope.selectedRemotes.pop();
          $scope.selectedRemotes.push(remote);
        }

        if ($scope.selectedRemotes.length == 1) {
          $scope.viewRemoteData = remote;
          $scope.fromRemoteData = remote;
          $scope.toRemoteData = {};
          $scope.diffData = {};
          fetchRemoteData($scope.selectedRemotes[0], function (data) {
            $scope.viewRemoteData = data;
            $scope.fromRemoteData = data;
          });
        }
        else if ($scope.selectedRemotes.length == 2) {
          $scope.toRemoteData = remote;
          fetchRemoteData($scope.selectedRemotes[1], function(data) {
            $scope.toRemoteData = data;
          });
        }
      };

      $scope.flipSelected = function() {
        var from = $scope.selectedRemotes[0];
        $scope.selectedRemotes[0] = $scope.selectedRemotes[1];
        $scope.selectedRemotes[1] = from;

        var fromData = $scope.fromRemoteData;
        $scope.fromRemoteData = $scope.toRemoteData;
        $scope.toRemoteData = fromData;
      };

      $scope.getActionLabel = function(apexClass) {
        if (apexClass.create) {
          return "Create";
        }
        else if (apexClass.update) {
          return "Update";
        }
        else if (apexClass.delete) {
          return "Delete";
        }
      };

      $scope.forceMergeAll = function() {
        $scope.forceMergeSelected();
      };

      $scope.forceMergeSelected = function() {
        var apexClasses = {
          creates: {},
          updates: {},
          deletes: []
        };

        angular.forEach($scope.diffData.apexclasses, function(apexClass) {
          if (apexClass.selectedForMerge) {
            if (apexClass.create) {
              apexClasses.creates[apexClass.from.Name] = apexClass.from.Body;
            }
            else if (apexClass.update) {
              apexClasses.updates[apexClass.to.Id] = apexClass.from.Body;
            }
            else if (apexClass.delete) {
              apexClasses.deletes.push(apexClass.to.Id);
            }
          }
        });


        var url = "";

        if ($scope.toRemoteData.org) {
          url = "/orgs/" + $scope.toRemoteData.id + "/metadata";
        }
        else if ($scope.toRemoteData.repo) {
          url = "/repos/" + $scope.toRemoteData.id + "/metadata";
        }

        $http
          .post(url, { apexClasses: apexClasses })
          .success(function(data, status, headers, config) {
            $scope.diffData = {};
            fetchRemoteData($scope.selectedRemotes[1], function(data) {
              data.org = $scope.toRemoteData.org;
              data.repo = $scope.toRemoteData.repo;
              $scope.toRemoteData = data;
            });
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      function getGitHubReadyStatus() {
        $http
          .get("/github/status")
          .success(function(data) {
            $scope.isGithubReady = data.isReady;
            if ($scope.isGithubReady) {
              fetchGitHubRepos();
              fetchRepos();
            }
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      }

      // fetch all of the available GitHub Repos
      function fetchGitHubRepos() {
        $http
          .get("/github/repos")
          .success(function(data) {
            $scope.githubRepos = data;
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      }

      $scope.addRepo = function(repo) {
        $http
          .post("/repos", repo)
          .success(function(data) {
            // remove the repos
            $scope.remotes = $scope.remotes.filter(function(item) { return !item.repo; });
            fetchRepos();
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };


      // init
      fetchOrgs();
      getGitHubReadyStatus();

    });

  angular.element(document).ready(function() {
    angular.resumeBootstrap();
  });

});